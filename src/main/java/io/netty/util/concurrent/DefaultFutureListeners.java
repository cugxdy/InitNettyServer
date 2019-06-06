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

	// GenericFutureListener��������
    private GenericFutureListener<? extends Future<?>>[] listeners;
    
    // GenericFutureListener�������鳤��
    private int size;
    
    // GenericProgressiveFutureListener������
    private int progressiveSize; // the number of progressive listeners

    @SuppressWarnings("unchecked")
    DefaultFutureListeners(
            GenericFutureListener<? extends Future<?>> first, GenericFutureListener<? extends Future<?>> second) {
        listeners = new GenericFutureListener[2];
        listeners[0] = first;
        listeners[1] = second;
        size = 2;
        
        // ��ΪGenericProgressiveFutureListenerʱ,progressiveSize�ͻ��ۼ�
        if (first instanceof GenericProgressiveFutureListener) {
            progressiveSize ++;
        }
        if (second instanceof GenericProgressiveFutureListener) {
            progressiveSize ++;
        }
    }

    public void add(GenericFutureListener<? extends Future<?>> l) {
        GenericFutureListener<? extends Future<?>>[] listeners = this.listeners;
        final int size = this.size; // �����С
        // �����Ƿ���Ҫ���ݲ���
        if (size == listeners.length) {
        	// �������ݲ���,��2����������չ��������
            this.listeners = listeners = Arrays.copyOf(listeners, size << 1);
        }
        // ��������Ԫ��
        listeners[size] = l;
        // ���������С
        this.size = size + 1;

        // ��lΪGenericProgressiveFutureListener����ʱ, progressiveSize ++
        if (l instanceof GenericProgressiveFutureListener) {
            progressiveSize ++;
        }
    }

    // ��listeners��ɾ��ָ����l
    public void remove(GenericFutureListener<? extends Future<?>> l) {
        final GenericFutureListener<? extends Future<?>>[] listeners = this.listeners;
        int size = this.size;
        for (int i = 0; i < size; i ++) {
            if (listeners[i] == l) {
                int listenersToMove = size - i - 1;
                if (listenersToMove > 0) {
                	// ������λ����
                    System.arraycopy(listeners, i + 1, listeners, i, listenersToMove);
                }
                // ������ĩβԪ����Ϊnull
                listeners[-- size] = null;
                this.size = size;

                // ��ΪGenericProgressiveFutureListener����ʱ,��ΪprogressiveSize --
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
