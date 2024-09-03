package net.sourceforge.opencamera.videosprerecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 循环缓存容器
 * @param <T>
 */
public class CircularBuffer<T> {
    private T[] buffer;
    private int head;
    private int tail;
    private int maxSize;
    private int count;


    public CircularBuffer(int size) {
        maxSize = size;
        buffer = (T[]) new Object[maxSize];
        head = 0;
        tail = 0;
        count = 0;
    }

    public void add(T item) {
        buffer[tail] = item;
        tail = (tail + 1) % maxSize;
        if (count == maxSize) {
            // 替换最旧的数据
            head = (head + 1) % maxSize;
        } else {
            count++;
        }
    }

    public List<T> getAll() {
        List<T> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(buffer[(head + i) % maxSize]);
        }
        return list;
    }

    public boolean clear() {
        buffer = null;
        maxSize = 0;
        head = 0;
        tail = 0;
        count = 0;
        return true;
    }

    public int size() {
        return count;
    }

    public int capacity() {
        return maxSize;
    }
}
