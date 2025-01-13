//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.nested;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import org.eclipse.jetty.ee9.nested.HttpOutput.Interceptor;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.IteratingCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * A Handler that can apply a {@link HttpOutput.Interceptor}
 * mechanism to buffer the entire response content until the output is closed.
 * This allows the commit to be delayed until the response is complete and thus
 * headers and response status can be changed while writing the body.
 * </p>
 * <p>
 * Note that the decision to buffer is influenced by the headers and status at the
 * first write, and thus subsequent changes to those headers will not influence the
 * decision to buffer or not.
 * </p>
 * <p>
 * Note also that there are no memory limits to the size of the buffer, thus
 * this handler can represent an unbounded memory commitment if the content
 * generated can also be unbounded.
 * </p>
 */
public class FileBufferedResponseHandler extends BufferedResponseHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(FileBufferedResponseHandler.class);

    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;
    private int _bufferSize = DEFAULT_BUFFER_SIZE;
    private boolean _useFileMapping = true;
    private Path _tempDir = new File(System.getProperty("java.io.tmpdir")).toPath();

    public Path getTempDir()
    {
        return _tempDir;
    }

    public void setTempDir(Path tempDir)
    {
        _tempDir = Objects.requireNonNull(tempDir);
    }

    public boolean isUseFileMapping()
    {
        return _useFileMapping;
    }

    public void setUseFileMapping(boolean useFileMapping)
    {
        this._useFileMapping = useFileMapping;
    }

    public int getBufferSize()
    {
        return _bufferSize;
    }

    public void setBufferSize(int bufferSize)
    {
        this._bufferSize = bufferSize;
    }

    @Override
    protected BufferedInterceptor newBufferedInterceptor(HttpChannel httpChannel, Interceptor interceptor)
    {
        return new FileBufferedInterceptor(httpChannel, interceptor);
    }

    class FileBufferedInterceptor implements BufferedResponseHandler.BufferedInterceptor
    {
        private final Interceptor _next;
        private final HttpChannel _channel;
        private Boolean _aggregating;
        private Path _filePath;
        private OutputStream _fileOutputStream;

        public FileBufferedInterceptor(HttpChannel httpChannel, Interceptor interceptor)
        {
            _next = interceptor;
            _channel = httpChannel;
        }

        @Override
        public Interceptor getNextInterceptor()
        {
            return _next;
        }

        @Override
        public void resetBuffer()
        {
            dispose();
            BufferedInterceptor.super.resetBuffer();
        }

        protected void dispose()
        {
            IO.close(_fileOutputStream);
            _fileOutputStream = null;
            _aggregating = null;

            if (_filePath != null)
            {
                try
                {
                    Files.delete(_filePath);
                }
                catch (Throwable t)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Could not immediately delete file (delaying to jvm exit) {}", _filePath, t);
                    _filePath.toFile().deleteOnExit();
                }
                _filePath = null;
            }
        }

        @Override
        public void write(ByteBuffer content, boolean last, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} write last={} {}", this, last, BufferUtil.toDetailString(content));

            // If we are not committed, must decide if we should aggregate or not.
            if (_aggregating == null)
                _aggregating = shouldBuffer(_channel, last);

            // If we are not aggregating, then handle normally.
            if (!_aggregating)
            {
                getNextInterceptor().write(content, last, callback);
                return;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("{} aggregating", this);

            try
            {
                if (BufferUtil.hasContent(content))
                    aggregate(content);
            }
            catch (Throwable t)
            {
                dispose();
                callback.failed(t);
                return;
            }

            if (last)
                commit(callback);
            else
                callback.succeeded();
        }

        private void aggregate(ByteBuffer content) throws IOException
        {
            if (_fileOutputStream == null)
            {
                // Create a new OutputStream to a file.
                _filePath = Files.createTempFile(_tempDir, "BufferedResponse", "");
                _fileOutputStream = Files.newOutputStream(_filePath, StandardOpenOption.WRITE);
            }

            BufferUtil.writeTo(content, _fileOutputStream);
        }

        private void commit(Callback callback)
        {
            if (_fileOutputStream == null)
            {
                // We have no content to write, signal next interceptor that we are finished.
                getNextInterceptor().write(BufferUtil.EMPTY_BUFFER, true, callback);
                return;
            }

            try
            {
                _fileOutputStream.close();
                _fileOutputStream = null;
            }
            catch (Throwable t)
            {
                dispose();
                callback.failed(t);
                return;
            }

            // Create an iterating callback to do the writing
            try
            {
                SendFileCallback sfcb = new SendFileCallback(this, _filePath, getBufferSize(), callback);
                sfcb.setUseFileMapping(isUseFileMapping());
                sfcb.iterate();
            }
            catch (IOException e)
            {
                callback.failed(e);
            }
        }
    }

    // TODO: can this be made generic enough to put into jetty-io somewhere?
    private static class SendFileCallback extends IteratingCallback
    {
        private static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE / 2;
        private final Path _filePath;
        private final long _fileLength;
        private final FileBufferedInterceptor _interceptor;
        private final Callback _callback;
        private final int _bufferSize;
        private long _pos = 0;
        private boolean _last = false;
        private Mode _mode = Mode.DISCOVER;

        enum Mode
        {
            DISCOVER,
            MAPPED,
            READ
        }

        public SendFileCallback(FileBufferedInterceptor interceptor, Path filePath, int bufferSize, Callback callback) throws IOException
        {
            _filePath = filePath;
            _fileLength = Files.size(filePath);
            _interceptor = interceptor;
            _callback = callback;
            _bufferSize = bufferSize;
        }

        public void setUseFileMapping(boolean useFileMapping)
        {
            if (!useFileMapping)
                _mode = Mode.READ; // don't even attempt file mapping
            else
                _mode = Mode.DISCOVER; // attempt file mapping first
        }

        @Override
        protected Action process() throws Exception
        {
            if (_last)
                return Action.SUCCEEDED;

            long len = Math.min(MAX_BUFFER_SIZE, _fileLength - _pos);
            ByteBuffer buffer = readByteBuffer(_filePath, _pos, len);
            if (buffer == null)
            {
                buffer = BufferUtil.EMPTY_BUFFER;
                _last = true;
            }
            else
            {
                _last = (_pos + buffer.remaining() == _fileLength);
            }
            int read = buffer.remaining();
            _interceptor.getNextInterceptor().write(buffer, _last, this);
            _pos += read;
            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            _interceptor.dispose();
            _callback.succeeded();
        }

        @Override
        protected void onFailure(Throwable cause)
        {
            _interceptor.dispose();
            _callback.failed(cause);
        }

        /**
         * Read the ByteBuffer from the path.
         *
         * @param path the path to read from
         * @param pos the position in the file to start from
         * @param len the length of the buffer to use for memory mapped mode
         * @return the buffer read, or null if no buffer has been read (such as being at EOF)
         * @throws IOException if unable to read from the path
         */
        private ByteBuffer readByteBuffer(Path path, long pos, long len) throws IOException
        {
            return switch (_mode)
            {
                case DISCOVER ->
                {
                    ByteBuffer buffer = toMapped(path, pos, len);
                    if (buffer == null)
                    {
                        // if we reached here, then file mapped byte buffers is not supported.
                        // we fall back to using traditional I/O instead.
                        buffer = toRead(path, pos);
                    }
                    yield buffer;
                }
                case MAPPED ->
                {
                    yield toMapped(path, pos, len);
                }
                case READ ->
                {
                    yield toRead(path, pos);
                }
            };
        }

        private ByteBuffer toMapped(Path path, long pos, long len) throws IOException
        {
            if (pos > _fileLength)
            {
                // attempt to read past end of file, consider this an EOF
                return null;
            }
            ByteBuffer buffer = BufferUtil.toMappedBuffer(path, pos, len);
            if (buffer != null)
                _mode = Mode.MAPPED;
            return buffer;
        }

        private ByteBuffer toRead(Path path, long pos) throws IOException
        {
            try (SeekableByteChannel channel = Files.newByteChannel(path))
            {
                _mode = Mode.READ;
                channel.position(pos);
                ByteBuffer buffer = ByteBuffer.allocateDirect(_bufferSize);
                int read = channel.read(buffer);
                if (read == -1)
                    return null; // indicating EOF
                buffer.flip();
                return buffer;
            }
        }
    }
}
