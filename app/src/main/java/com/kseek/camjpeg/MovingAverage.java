package com.kseek.camjpeg;

final class MovingAverage
{
    private final int numValues;
    private final long[] values;
    private int endPos = 0;
    private int lengthMov = 0;
    private long sum = 0L;

    MovingAverage(final int numValues)
    {
        super();
        this.numValues = numValues;
        values = new long[numValues];
    }

    void update(final long value)
    {
        sum -= values[endPos];
        values[endPos] = value;
        endPos = (endPos + 1) % numValues;
        if (lengthMov < numValues) {
            lengthMov++;
        }
        sum += value;
    }

    double getAverage()
    {
        return sum / (double) lengthMov;
    }
}

