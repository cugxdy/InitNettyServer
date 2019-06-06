/*
 * Copyright 2014 The Netty Project
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

package io.netty.util.internal;

import io.netty.util.concurrent.FastThreadLocal;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The internal data structure that stores the thread-local variables for Netty and all {@link FastThreadLocal}s.
 * Note that this class is for internal use only and is subject to change at any time.  Use {@link FastThreadLocal}
 * unless you know what you are doing.
 */
class UnpaddedInternalThreadLocalMap {

	// ThreadLocal:线程本地变量
	// get()方法是用来获取ThreadLocal在当前线程中保存的变量副本.
	// set()用来设置当前线程中变量的副本.
	// remove()用来移除当前线程中变量的副本.
    static final ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = new ThreadLocal<InternalThreadLocalMap>();
    
    // 原子类的Integer类型
    static final AtomicInteger nextIndex = new AtomicInteger();

    /** Used by {@link FastThreadLocal} */
    Object[] indexedVariables;

    // Core thread-locals
    // 获取监听器个数。
    int futureListenerStackDepth;
    // 用于在socket读时的栈深度
    int localChannelReaderStackDepth;
    // 处理器handler是否为共享模式
    Map<Class<?>, Boolean> handlerSharableCache;
    // 存储哈希码
    IntegerHolder counterHashCode;
    // ThreadLocalRandom对象(随机数生成器)
    ThreadLocalRandom random;
    Map<Class<?>, TypeParameterMatcher> typeParameterMatcherGetCache;
    
    Map<Class<?>, Map<String, TypeParameterMatcher>> typeParameterMatcherFindCache;

    // String-related thread-locals
    // StringBuilder对象
    StringBuilder stringBuilder;
    // 编码类型
    Map<Charset, CharsetEncoder> charsetEncoderCache;
    // 解码类型
    Map<Charset, CharsetDecoder> charsetDecoderCache;

    // ArrayList-related thread-locals
    ArrayList<Object> arrayList;

    // 存储对象数组
    UnpaddedInternalThreadLocalMap(Object[] indexedVariables) {
        this.indexedVariables = indexedVariables;
    }
}
