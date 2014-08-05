package com.ldaniels528.verify.subsystems.kafka

import com.ldaniels528.verify.io.EndPoint

/**
 * Type-safe Broker definition
 * @author lawrence.daniels@gmail.com
 */
case class Broker(host: String, port: Int) extends EndPoint

/**
 * Broker Companion Object
 * @author lawrence.daniels@gmail.com
 */
object Broker {

  def apply(pair: String): Broker = {
    pair.split(":").toList match {
      case host :: port :: Nil => new Broker(host, port.toInt)
      case _ =>
        throw new IllegalStateException(s"Invalid end-point '$pair'; Format 'host:port' expected")
    }
  }


}