/*
 Copyright 2013 Twitter, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.twitter.summingbird.example

import com.twitter.algebird.Monoid
import com.twitter.bijection.{ Base64String, Bijection, Codec, Injection }
import com.twitter.bijection.netty.Implicits._
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.memcached.KetamaClientBuilder
import com.twitter.finagle.memcached.protocol.text.Memcached
import com.twitter.finagle.transport.Transport
import com.twitter.storehaus.Store
import com.twitter.storehaus.algebra.MergeableStore
import com.twitter.storehaus.memcache.{ HashEncoder, MemcacheStore }
import com.twitter.util.Duration
import org.jboss.netty.buffer.ChannelBuffer

/**
 * TODO: Delete when https://github.com/twitter/storehaus/pull/121 is
 * merged into Storehaus and Storehaus sees its next release. This
 * pull req will make it easier to create Memcache store instances.
 */
object Memcache {
  val DEFAULT_TIMEOUT = Duration.fromSeconds(1)

  def client = {
    val builder = ClientBuilder()
      .name("memcached")
      .retries(2)
      .tcpConnectTimeout(DEFAULT_TIMEOUT)
      .requestTimeout(DEFAULT_TIMEOUT)
      .connectTimeout(DEFAULT_TIMEOUT)
      .hostConnectionLimit(1)
      .codec(Memcached())

    val liveness = builder.params[Transport.Liveness].copy(readTimeout = DEFAULT_TIMEOUT)
    val liveBuilder = builder.configured(liveness)

    KetamaClientBuilder()
      .clientBuilder(liveBuilder)
      .nodes("localhost:11211")
      .build()
  }

  /**
   * Returns a function that encodes a key to a Memcache key string
   * given a unique namespace string.
   */
  def keyEncoder[T](namespace: String)(implicit inj: Codec[T]): T => String = { key: T =>
    def concat(bytes: Array[Byte]): Array[Byte] =
      namespace.getBytes ++ bytes

    (inj.andThen(concat _)
      .andThen(HashEncoder())
      .andThen(Bijection.connect[Array[Byte], Base64String]))(key).str
  }

  def store[K: Codec, V: Codec](keyPrefix: String): Store[K, V] = {
    implicit val valueToBuf = Injection.connect[V, Array[Byte], ChannelBuffer]
    MemcacheStore(client)
      .convert(keyEncoder[K](keyPrefix))
  }

  def mergeable[K: Codec, V: Codec: Monoid](keyPrefix: String): MergeableStore[K, V] =
    MergeableStore.fromStore(store[K, V](keyPrefix))
}
