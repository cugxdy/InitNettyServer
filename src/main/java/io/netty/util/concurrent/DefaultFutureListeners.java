/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import java.util.Arrays;

final class DefaultFutureListeners {

	// GenericFutureListener类型数组
    private GenericFutureListener<? extends Future<?>>[] listeners;
    
    // GenericFutureListener类型数组长度
    private int size;
    
    // GenericProgressiveFutureListener计数器
    private int progressiveSize; // the number of progressive listeners

    @SuppressWarnings("unchecked")
    DefaultFutureListeners(
            GenericFutureListener<? extends Future<?>> first, GenericFutureListener<? extends Future<?>> second) {
        listeners = new GenericFutureListener[2];
        listeners[0] = first;
        listeners[1] = second;
        size = 2;
        
        // 当为GenericProgressiveFutureListener时,progressiveSize就会累加
        if (first instanceof GenericProgressiveFutureListener) {
            progressiveSize ++;
        }
        if (second instanceof GenericProgressiveFutureListener) {
            progressiveSize ++;
        }
    }

    public void add(GenericFutureListener<? extends Future<?>> l) {
        GenericFutureListener<? extends Future<?>>[] listeners = this.listeners;
        final int size = this.size; // 数组大小
        // 检验是否需要扩容操作
        if (size == listeners.length) {
        	// 数组扩容操作,以2倍的容量扩展数组容量
            this.listeners = listeners = Arrays.copyOf(listeners, size << 1);
        }
        // 设置数组元素
        listeners[size] = l;
        // 设置数组大小
        this.size = size + 1;

        // 当l为GenericProgressiveFutureListener类型时, progressiveSize ++
        if (l instanceof GenericProgressiveFutureListener) {
            progressiveSize ++;
        }
    }

    // 从listeners中删除指定的l
    public void remove(GenericFutureListener<? extends Future<?>> l) {
        final GenericFutureListener<? extends Future<?>>[] listeners = this.listeners;
        int size = this.size;
        for (int i = 0; i < size; i ++) {
            if (listeners[i] == l) {
                int listenersToMove = size - i - 1;
                if (listenersToMove > 0) {
                	// 数组移位操作
                    System.arraycopy(listeners, i + 1, listeners, i, listenersToMove);
                }
                // 将数组末尾元素置为null
                listeners[-- size] = null;
                this.size = size;

                // 当为GenericProgressiveFutureListener类型时,即为progressiveSize --
                if (l instanceof GenericProgressiveFutureListener) {
                    progressiveSize --;
                }
                return;
            }
        }
    }

    public GenericFutureListener<? extends Future<?>>[] listeners() {
        return listeners;
    }

    public int size() {
        return size;
    }

    public int progressiveSize() {
        return progressiveSize;
    }
}
