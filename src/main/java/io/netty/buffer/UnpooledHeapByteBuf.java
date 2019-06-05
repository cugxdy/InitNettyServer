/*
 * Copyright 2012 The Netty Project
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
package io.netty.buffer;

import io.netty.util.internal.PlatformDependent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * Big endian Java heap buffer implementation. It is recommended to use
 * {@link UnpooledByteBufAllocator#heapBuffer(int, int)}, {@link Unpooled#buffer(int)} and
 * {@link Unpooled#wrappedBuffer(byte[])} instead of calling the constructor explicitly.
 */
public class UnpooledHeapByteBuf extends AbstractReferenceCountedByteBuf {

	// ����UnpooledHeapByteBuf���ڴ����
    private final ByteBufAllocator alloc;
    // byte������Ϊ������
    byte[] array;
    // ����ʵ��Netty ByteBuf��JDK NIO ByteBuffer��ת��
    private ByteBuffer tmpNioBuf;

    /**
     * Creates a new heap buffer with a newly allocated byte array.
     *
     * @param initialCapacity the initial capacity of the underlying byte array
     * @param maxCapacity the max capacity of the underlying byte array
     */
    public UnpooledHeapByteBuf(ByteBufAllocator alloc, int initialCapacity, int maxCapacity) {
        super(maxCapacity);

        checkNotNull(alloc, "alloc");

        // ��ʼ���� <= MAX����
        if (initialCapacity > maxCapacity) {
            throw new IllegalArgumentException(String.format(
                    "initialCapacity(%d) > maxCapacity(%d)", initialCapacity, maxCapacity));
        }

        // �����ڴ������
        this.alloc = alloc;
        setArray(allocateArray(initialCapacity));
        // ���ö�д����
        setIndex(0, 0);
    }

    /**
     * Creates a new heap buffer with an existing byte array.
     *
     * @param initialArray the initial underlying byte array
     * @param maxCapacity the max capacity of the underlying byte array
     */
    protected UnpooledHeapByteBuf(ByteBufAllocator alloc, byte[] initialArray, int maxCapacity) {
        super(maxCapacity);

        checkNotNull(alloc, "alloc");
        checkNotNull(initialArray, "initialArray");

        if (initialArray.length > maxCapacity) {
            throw new IllegalArgumentException(String.format(
                    "initialCapacity(%d) > maxCapacity(%d)", initialArray.length, maxCapacity));
        }

        // �����ڴ������
        this.alloc = alloc;
        setArray(initialArray);
        // ���ö�д����
        setIndex(0, initialArray.length);
    }

    // ������ʼ����ΪinitialCapacity���ֽ�����
    byte[] allocateArray(int initialCapacity) {
    	// �����µ��ֽڻ���������
        return new byte[initialCapacity];
    }

    void freeArray(byte[] array) {
        // NOOP
    }

    // ����array��tmpNioBuf�ֶ�
    private void setArray(byte[] initialArray) {
        array = initialArray;
        // JDK�汾��Bytebuffer
        tmpNioBuf = null;
    }

    @Override
    public ByteBufAllocator alloc() {
        return alloc;
    }

    @Override
    public ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override// ���ڶ��ڴ�ʵ�ֵ�ByteBuf,���᷵��false
    public boolean isDirect() {
        return false;
    }

    @Override// ���������С
    public int capacity() {
        ensureAccessible();
        return array.length;
    }

    @Override
    public ByteBuf capacity(int newCapacity) {
    	// ����newCapacity�Ϸ���
        checkNewCapacity(newCapacity);

        int oldCapacity = array.length;
        byte[] oldArray = array;
        
        if (newCapacity > oldCapacity) {
        	// �����µĻ������ֽ�����
            byte[] newArray = allocateArray(newCapacity);
            // �ڴ渴��,���ɵ��ֽ����鸴�Ƶ��´������ֽ�������
            System.arraycopy(oldArray, 0, newArray, 0, oldArray.length);
            // �����ֽ����黺����
            setArray(newArray);
            // �ͷžɵ��ֽ�����
            freeArray(oldArray);
        } else if (newCapacity < oldCapacity) {
            byte[] newArray = allocateArray(newCapacity);
            // ��ȡ������
            int readerIndex = readerIndex();
            if (readerIndex < newCapacity) {
            	// ��ȡд����
                int writerIndex = writerIndex();
                if (writerIndex > newCapacity) {
                    writerIndex(writerIndex = newCapacity);
                }
                // ���������е�readerIndex  - (writerIndex - readerIndex)�ֽڸ��Ƶ��µ��ֽ�������
                System.arraycopy(oldArray, readerIndex, newArray, readerIndex, writerIndex - readerIndex);
            } else {
                setIndex(newCapacity, newCapacity);
            }
            setArray(newArray);
            freeArray(oldArray);
        }
        return this;
    }

    @Override// �Ƿ�����ֽ�����ʵ��
    public boolean hasArray() {
        return true;
    }

    @Override // �����ֽ�����
    public byte[] array() {
        ensureAccessible();
        return array;
    }

    @Override// ����ƫ����Ϊ0
    public int arrayOffset() {
        return 0;
    }

    @Override
    public boolean hasMemoryAddress() {
        return false;
    }

    @Override // �׳��쳣
    public long memoryAddress() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) {
        checkDstIndex(index, length, dstIndex, dst.capacity());
        if (dst.hasMemoryAddress()) {
        	// �ڴ渴�Ƹ���
            PlatformDependent.copyMemory(array, index, dst.memoryAddress() + dstIndex, length);
        } else if (dst.hasArray()) {
        	// ����System.arraycopy()����
            getBytes(index, dst.array(), dst.arrayOffset() + dstIndex, length);
        } else {
            dst.setBytes(dstIndex, array, index, length);
        }
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
        checkDstIndex(index, length, dstIndex, dst.length);
        // public static void arraycopy(Object src, int srcPos, Object dest, int destPos, int length)
        // src:Դ����;
        // srcPos:Դ����Ҫ���Ƶ���ʼλ��;
        // dest:Ŀ������;
        // destPos:Ŀ��������õ���ʼλ��;
        // length:���Ƶĳ���;
        // ע�⣺src �� dest��������ͬ���ͻ��߿��Խ���ת�����͵����飮
        // ֵ����:һά���鲻��������
        System.arraycopy(array, index, dst, dstIndex, length);
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuffer dst) {
        checkIndex(index, dst.remaining());
        dst.put(array, index, dst.remaining());
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, OutputStream out, int length) throws IOException {
        ensureAccessible();
        // ��arrayд��OutputStream��
        out.write(array, index, length);
        return this;
    }

    @Override
    public int getBytes(int index, GatheringByteChannel out, int length) throws IOException {
        ensureAccessible();
        return getBytes(index, out, length, false);
    }

    private int getBytes(int index, GatheringByteChannel out, int length, boolean internal) throws IOException {
        ensureAccessible();
        ByteBuffer tmpBuf;
        if (internal) {
            tmpBuf = internalNioBuffer();
        } else {
            tmpBuf = ByteBuffer.wrap(array);
        }
        // ��index��index + length�е��ֽ�д�뵽GatheringByteChannel��
        return out.write((ByteBuffer) tmpBuf.clear().position(index).limit(index + length));
    }

    @Override
    public int readBytes(GatheringByteChannel out, int length) throws IOException {
    	// �ɶ��ֽ�������
        checkReadableBytes(length);
        int readBytes = getBytes(readerIndex, out, length, true);
        // ���ÿɶ��ֽ�����
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length) {
        checkSrcIndex(index, length, srcIndex, src.capacity());
        if (src.hasMemoryAddress()) {
            PlatformDependent.copyMemory(src.memoryAddress() + srcIndex, array, index, length);
        } else  if (src.hasArray()) {
        	// ��src���ֽ����õ�array�ֽ�������
            setBytes(index, src.array(), src.arrayOffset() + srcIndex, length);
        } else {
            src.getBytes(srcIndex, array, index, length);
        }
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, byte[] src, int srcIndex, int length) {
    	// ���������ֶ��Ƿ���ȷ
        checkSrcIndex(index, length, srcIndex, src.length);
        // ��src��ʼλ��ΪsrcIndex,����Ϊlength���ֽ����鸴�Ƶ�������Ϊindex��array�ֽ�������
        System.arraycopy(src, srcIndex, array, index, length);
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuffer src) {
        ensureAccessible();
        // ��ByteBuffer�е��ֽڸ��Ƶ�array�ֽ�������
        src.get(array, index, src.remaining());
        return this;
    }

    @Override
    public int setBytes(int index, InputStream in, int length) throws IOException {
        ensureAccessible();
        // ��������InputStream�и����ֽ�������array��ȥ
        return in.read(array, index, length);
    }

    @Override
    public int setBytes(int index, ScatteringByteChannel in, int length) throws IOException {
        ensureAccessible();
        try {
        	// ��Socket�ж�ȡ�ֽ����鵽������java.nio.ByteBuffer�У�������ʼλpositionΪindex,limitΪindex + length
            return in.read((ByteBuffer) internalNioBuffer().clear().position(index).limit(index + length));
        } catch (ClosedChannelException ignored) {
            return -1;
        }
    }

    @Override
    public int nioBufferCount() {
        return 1;
    }

    @Override // ���ֽ�����array�����ByteBuffer��
    public ByteBuffer nioBuffer(int index, int length) {
        ensureAccessible();
        return ByteBuffer.wrap(array, index, length).slice();
    }

    @Override
    public ByteBuffer[] nioBuffers(int index, int length) {
        return new ByteBuffer[] { nioBuffer(index, length) };
    }

    @Override
    public ByteBuffer internalNioBuffer(int index, int length) {
        checkIndex(index, length);
        return (ByteBuffer) internalNioBuffer().clear().position(index).limit(index + length);
    }

    @Override
    public byte getByte(int index) {
        ensureAccessible();
        return _getByte(index);
    }

    @Override
    protected byte _getByte(int index) {
    	// ��ȡָ��λ�õ��ֽ�
        return HeapByteBufUtil.getByte(array, index);
    }

    @Override
    public short getShort(int index) {
        ensureAccessible();
        return _getShort(index);
    }

    @Override
    protected short _getShort(int index) {
    	// ��array��index��ȡShort�ֽ�
        return HeapByteBufUtil.getShort(array, index);
    }

    @Override
    public int getUnsignedMedium(int index) {
        ensureAccessible();
        return _getUnsignedMedium(index);
    }

    @Override
    protected int _getUnsignedMedium(int index) {
        return HeapByteBufUtil.getUnsignedMedium(array, index);
    }

    @Override
    public int getInt(int index) {
        ensureAccessible();
        return _getInt(index);
    }

    @Override
    protected int _getInt(int index) {
    	// ��ȡint���͵��ֽ���
        return HeapByteBufUtil.getInt(array, index);
    }

    @Override
    public long getLong(int index) {
        ensureAccessible();
        return _getLong(index);
    }

    @Override
    protected long _getLong(int index) {
    	// ��ȡLong���͵�
        return HeapByteBufUtil.getLong(array, index);
    }

    @Override
    public ByteBuf setByte(int index, int value) {
        ensureAccessible();
        _setByte(index, value);
        return this;
    }

    @Override
    protected void _setByte(int index, int value) {
    	// ��ָ��λ��index���value
        HeapByteBufUtil.setByte(array, index, value);
    }

    @Override
    public ByteBuf setShort(int index, int value) {
        ensureAccessible();
        _setShort(index, value);
        return this;
    }

    @Override
    protected void _setShort(int index, int value) {
        HeapByteBufUtil.setShort(array, index, value);
    }

    @Override
    public ByteBuf setMedium(int index, int   value) {
        ensureAccessible();
        _setMedium(index, value);
        return this;
    }

    @Override
    protected void _setMedium(int index, int value) {
        HeapByteBufUtil.setMedium(array, index, value);
    }

    @Override
    public ByteBuf setInt(int index, int   value) {
        ensureAccessible();
        _setInt(index, value);
        return this;
    }

    @Override
    protected void _setInt(int index, int value) {
        HeapByteBufUtil.setInt(array, index, value);
    }

    @Override
    public ByteBuf setLong(int index, long  value) {
        ensureAccessible();
        _setLong(index, value);
        return this;
    }

    @Override
    protected void _setLong(int index, long value) {
        HeapByteBufUtil.setLong(array, index, value);
    }

    @Override // ���Ʋ�����UnpooledHeapByteBuf����
    public ByteBuf copy(int index, int length) {
        checkIndex(index, length);
        byte[] copiedArray = new byte[length];
        // �ڴ渴�ƺ���
        System.arraycopy(array, index, copiedArray, 0, length);
        return new UnpooledHeapByteBuf(alloc(), copiedArray, maxCapacity());
    }

    private ByteBuffer internalNioBuffer() {
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        if (tmpNioBuf == null) {
        	// ���ֽ�����arrayת����tmpNioBuf��ȥ
            this.tmpNioBuf = tmpNioBuf = ByteBuffer.wrap(array);
        }
        return tmpNioBuf;
    }

    @Override
    protected void deallocate() {
        freeArray(array);
        array = null;
    }

    @Override
    public ByteBuf unwrap() {
        return null;
    }
}
