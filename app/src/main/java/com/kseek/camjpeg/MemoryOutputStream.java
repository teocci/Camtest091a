package com.kseek.camjpeg;

import java.io.IOException;
import java.io.OutputStream;

final class MemoryOutputStream extends OutputStream
{
    private final byte[] buffer;
    private int bufferLength = 0;

    MemoryOutputStream(final int size)
    {
        this(new byte[size]);
    } // constructor(int)

    MemoryOutputStream(final byte[] newBuffer)
    {
        super();
        buffer = newBuffer;
    }

    @Override
    public void write(final byte[] newBuffer, final int offset, final int count)
            throws IOException
    {
        checkSpace(count);
        System.arraycopy(newBuffer, offset, buffer, bufferLength, count);
        bufferLength += count;
    }

    @Override
    public void write(final byte[] newBuffer) throws IOException
    {
        checkSpace(newBuffer.length);
        System.arraycopy(newBuffer, 0, buffer, bufferLength, newBuffer.length);
        bufferLength += newBuffer.length;
    }

    @Override
    public void write(final int oneByte) throws IOException
    {
        checkSpace(1);
        buffer[bufferLength++] = (byte) oneByte;
    }

    private void checkSpace(final int length) throws IOException
    {
        if (bufferLength + length >= buffer.length) {
            throw new IOException("insufficient space in buffer");
        }
    }

    void seek(final int index)
    {
        bufferLength = index;
    }

    byte[] getBuffer()
    {
        return buffer;
    }

    int getLength()
    {
        return bufferLength;
    }

}

