package com.ldaniels528.trifecta.modules.kafka

import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date

import com.ldaniels528.trifecta.command._
import com.ldaniels528.trifecta.decoders.AvroDecoder
import com.ldaniels528.trifecta.util.ParsingHelper._
import kafka.common.TopicAndPartition
import com.ldaniels528.trifecta.modules._
import com.ldaniels528.trifecta.support.io.KeyAndMessage
import com.ldaniels528.trifecta.support.kafka.KafkaFacade._
import com.ldaniels528.trifecta.support.kafka.KafkaMicroConsumer.{BrokerDetails, MessageData, contentFilter}
import com.ldaniels528.trifecta.support.kafka._
import com.ldaniels528.trifecta.support.messaging.MessageDecoder
import com.ldaniels528.trifecta.support.messaging.logic.Condition
import com.ldaniels528.trifecta.support.messaging.logic.ConditionCompiler._
import com.ldaniels528.trifecta.support.zookeeper.ZKProxy
import com.ldaniels528.trifecta.util.TxUtils._
import com.ldaniels528.trifecta.vscript.VScriptRuntime.ConstantValue
import com.ldaniels528.trifecta.vscript.Variable
import com.ldaniels528.trifecta.{TxConfig, TxRuntimeContext}
import net.liftweb.json.JValue
import org.apache.avro.generic.GenericRecord

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

/**
 * Apache Kafka Module
 * @author Lawrence Daniels <lawrence.daniels@gmail.com>
 */
class KafkaModule(config: TxConfig) extends Module with AvroReading {
  private implicit val formats = net.liftweb.json.DefaultFormats
  private var zkProxy_? : Option[ZKProxy] = None
  private val out: PrintStream = config.out

  // set the default correlation ID
  private val correlationId: Int = (Math.random * Int.MaxValue).toInt

  // incoming messages cache
  private var incomingMessageCache = Map[TopicAndPartition, Inbound]()
  private var lastInboundCheck: Long = _

  // define the offset for message cursor navigation commands
  private val cursors = mutable.Map[String, KafkaCursor]()
  private var currentTopic: Option[String] = None

  // create the facade
  private[kafka] val facade = new KafkaFacade(correlationId)

  /**
   * Returns the list of brokers from Zookeeper
   * @return the list of [[Broker]]s
   */
  private lazy val brokers: Seq[Broker] = facade.brokers

  def defaultFetchSize: Int = config.getOrElse("defaultFetchSize", 65536)

  def defaultFetchSize_=(sizeInBytes: Int) = config.set("defaultFetchSize", sizeInBytes)

  // the bound commands
  override def getCommands(implicit rt: TxRuntimeContext): Seq[Command] = Seq(
    Command(this, "kbrokers", getBrokers, UnixLikeParams(), help = "Returns a list of the brokers from ZooKeeper"),
    Command(this, "kcommit", commitOffset, UnixLikeParams(Seq("topic" -> false, "partition" -> false, "groupId" -> true, "offset" -> true), Seq("-m" -> "metadata")), help = "Commits the offset for a given topic and group"),
    Command(this, "kconsumers", getConsumers, UnixLikeParams(Nil, Seq("-t" -> "topicPrefix", "-c" -> "consumerPrefix")), help = "Returns a list of the consumers from ZooKeeper"),
    Command(this, "kconnect", connect, UnixLikeParams(Seq("host" -> false, "port" -> false)), help = "Establishes a connection to Zookeeper"),
    Command(this, "kcount", countMessages, UnixLikeParams(Seq("field" -> true, "operator" -> true, "value" -> true)), help = "Counts the messages matching a given condition"),
    Command(this, "kcursor", getCursor, UnixLikeParams(Nil, Seq("-t" -> "topicPrefix")), help = "Displays the message cursor(s)"),
    Command(this, "kfetch", fetchOffsets, UnixLikeParams(Seq("topic" -> false, "partition" -> false, "groupId" -> true)), help = "Retrieves the offset for a given topic and group"),
    Command(this, "kfetchsize", fetchSizeGetOrSet, UnixLikeParams(Seq("fetchSize" -> false)), help = "Retrieves or sets the default fetch size for all Kafka queries"),
    Command(this, "kfind", findMessages, UnixLikeParams(Seq("field" -> true, "operator" -> true, "value" -> true), Seq("-a" -> "avroSchema", "-o" -> "outputSource", "-t" -> "topic")), "Finds messages matching a given condition and exports them to a topic"),
    Command(this, "kfindone", findOneMessage, UnixLikeParams(Seq("field" -> true, "operator" -> true, "value" -> true), Seq("-a" -> "avroSchema", "-f" -> "format", "-o" -> "outputSource", "-t" -> "topic")), "Returns the first occurrence of a message matching a given condition"),
    Command(this, "kfindnext", findNextMessage, UnixLikeParams(Seq("field" -> true, "operator" -> true, "value" -> true), Seq("-a" -> "avroSchema", "-f" -> "format", "-o" -> "outputSource", "-p" -> "partition", "-t" -> "topic")), "Returns the first occurrence of a message matching a given condition"),
    Command(this, "kfirst", getFirstMessage, UnixLikeParams(Seq("topic" -> false, "partition" -> false), Seq("-a" -> "avroSchema", "-f" -> "format", "-o" -> "outputSource", "-p" -> "partition")), help = "Returns the first message for a given topic"),
    Command(this, "kget", getMessage, UnixLikeParams(Seq("topic" -> false, "partition" -> false, "offset" -> false), Seq("-a" -> "avroSchema", "-f" -> "format", "-o" -> "outputSource", "-p" -> "partition", "-ts" -> "YYYY-MM-DDTHH:MM:SS")), help = "Retrieves the message at the specified offset for a given topic partition"),
    Command(this, "kgetkey", getMessageKey, UnixLikeParams(Seq("topic" -> false, "partition" -> false, "offset" -> false), Seq("-f" -> "format", "-s" -> "fetchSize")), help = "Retrieves the key of the message at the specified offset for a given topic partition"),
    Command(this, "kgetsize", getMessageSize, UnixLikeParams(Seq("topic" -> false, "partition" -> false, "offset" -> false), Seq("-s" -> "fetchSize")), help = "Retrieves the size of the message at the specified offset for a given topic partition"),
    Command(this, "kgetminmax", getMessageMinMaxSize, UnixLikeParams(Seq("topic" -> false, "partition" -> false, "startOffset" -> true, "endOffset" -> true), Seq("-s" -> "fetchSize")), help = "Retrieves the smallest and largest message sizes for a range of offsets for a given partition"),
    Command(this, "kinbound", inboundMessages, UnixLikeParams(Seq("topicPrefix" -> false), Seq("-w" -> "wait-time")), help = "Retrieves a list of topics with new messages (since last query)"),
    Command(this, "klast", getLastMessage, UnixLikeParams(Seq("topic" -> false, "partition" -> false), Seq("-a" -> "avroSchema", "-f" -> "format", "-o" -> "outputSource", "-p" -> "partition")), help = "Returns the last message for a given topic"),
    Command(this, "kls", getTopics, UnixLikeParams(Seq("topicPrefix" -> false), Seq("-l" -> "listMode")), help = "Lists all existing topics"),
    Command(this, "knext", getNextMessage, UnixLikeParams(Seq("delta" -> false), flags = Seq("-a" -> "avroSchema", "-f" -> "format", "-o" -> "outputSource")), help = "Attempts to retrieve the next message"),
    Command(this, "kprev", getPreviousMessage, UnixLikeParams(Seq("delta" -> false), flags = Seq("-a" -> "avroSchema", "-f" -> "format", "-o" -> "outputSource")), help = "Attempts to retrieve the message at the previous offset"),
    Command(this, "kput", publishMessage, UnixLikeParams(Seq("topic" -> false, "key" -> true, "message" -> true)), help = "Publishes a message to a topic"),
    Command(this, "kreset", resetConsumerGroup, UnixLikeParams(Seq("topic" -> false, "groupId" -> true)), help = "Sets a consumer group ID to zero for all partitions"),
    Command(this, "kstats", getStatistics, UnixLikeParams(Seq("topic" -> false, "beginPartition" -> false, "endPartition" -> false)), help = "Returns the partition details for a given topic"),
    Command(this, "kswitch", switchCursor, UnixLikeParams(Seq("topic" -> true)), help = "Switches the currently active topic cursor"))

  /**
   * Returns a Kafka Topic input source
   * @param url the given input URL (e.g. "topic:shocktrade.quotes.avro")
   * @return the option of a Kafka Topic input source
   */
  override def getInputSource(url: String): Option[KafkaTopicInputSource] = {
    url.extractProperty("topic:") map (new KafkaTopicInputSource(brokers, _))
  }

  /**
   * Returns a Kafka Topic output source
   * @param url the given output URL (e.g. "topic:shocktrade.quotes.avro")
   * @return the option of a Kafka Topic output source
   */
  override def getOutputSource(url: String): Option[KafkaTopicOutputSource] = {
    url.extractProperty("topic:") map (new KafkaTopicOutputSource(brokers, _))
  }

  override def getVariables: Seq[Variable] = Seq(
    Variable("defaultFetchSize", ConstantValue(Option(65536)))
  )

  override def moduleName = "kafka"

  override def moduleLabel = "kafka"

  override def prompt: String = cursor map (c => s"${c.topic}/${c.partition}:${c.offset}") getOrElse "/"

  override def shutdown() = zkProxy_?.foreach(_.close())

  override def supportedPrefixes: Seq[String] = Seq("topic")

  /**
   * Returns the cursor for the current topic partition
   * @return the cursor for the current topic partition
   */
  private def cursor: Option[KafkaCursor] = currentTopic.flatMap(cursors.get)

  /**
   * Commits the offset for a given topic and group ID
   * @example kcommit com.shocktrade.alerts 0 devc0 123678
   * @example kcommit devc0 123678
   */
  def commitOffset(params: UnixLikeArgs) {
    // get the arguments (topic, partition, groupId and offset)
    val (topic, partition, groupId, offset) = params.args match {
      case aGroupId :: anOffset :: Nil => cursor map (c => (c.topic, c.partition, aGroupId, parseOffset(anOffset))) getOrElse dieNoCursor
      case aTopic :: aPartition :: aGroupId :: anOffset :: Nil => (aTopic, parsePartition(aPartition), aGroupId, parseOffset(anOffset))
      case _ => dieSyntax(params)
    }

    // commit the offset
    facade.commitOffset(topic, partition, groupId, offset, params("-m"))
  }

  /**
   * Establishes a connection to Zookeeper
   * @example kconnect
   * @example kconnect localhost
   * @example kconnect localhost:2181
   */
  def connect(params: UnixLikeArgs) {
    // determine the requested end-point
    val connectionString = params.args match {
      case Nil => config.zooKeeperConnect
      case zconnectString :: Nil => zconnectString
      case _ => dieSyntax(params)
    }

    // connect to the remote peer
    zkProxy_?.foreach(_.close())
    zkProxy_? = Option(ZKProxy(connectionString))
  }

  /**
   * Counts the messages matching a given condition [references cursor]
   * @example kcount frequency >= 1200
   */
  def countMessages(params: UnixLikeArgs): Future[Long] = {
    // get the topic and partition from the cursor
    val (topic, decoder) = cursor map (c => (c.topic, c.decoder)) getOrElse dieNoCursor

    // get the criteria
    val Seq(field, operator, value, _*) = params.args
    val conditions = Seq(compile(compile(field, operator, value), decoder))

    // perform the count
    facade.countMessages(topic, conditions, decoder)
  }

  /**
   * Returns the offsets for a given topic and group ID
   * @example kfetch com.shocktrade.alerts 0 dev
   * @example kfetch dev
   */
  def fetchOffsets(params: UnixLikeArgs): Option[Long] = {
    // get the arguments (topic, partition, groupId)
    val (topic, partition, groupId) = params.args match {
      case aGroupId :: Nil => cursor map (c => (c.topic, c.partition, aGroupId)) getOrElse dieNoCursor
      case aTopic :: aPartition :: aGroupId :: Nil => (aTopic, parsePartition(aPartition), aGroupId)
      case _ => dieSyntax(params)
    }

    // perform the action
    facade.fetchOffsets(topic, partition, groupId)
  }

  /**
   * Retrieves or sets the default fetch size for all Kafka queries
   * @example kfetchsize
   * @example kfetchsize 65536
   */
  def fetchSizeGetOrSet(params: UnixLikeArgs) = {
    params.args.headOption match {
      case Some(fetchSize) => defaultFetchSize = parseInt("fetchSize", fetchSize)
      case None => defaultFetchSize
    }
  }

  /**
   * Returns the first message that corresponds to the given criteria
   * @example kfindone volume > 1000000
   * @example kfindone volume > 1000000 -a file:avro/quotes.avsc
   * @example kfindone volume > 1000000 -t shocktrade.quotes.avro -a file:avro/quotes.avsc
   * @example kfindone lastTrade < 1 and volume > 1000000 -a file:avro/quotes.avsc
   */
  def findOneMessage(params: UnixLikeArgs)(implicit rt: TxRuntimeContext) = {
    // was a topic and/or Avro decoder specified?
    val topic_? = params("-t")
    val avro_? = getAvroDecoder(params)(config)

    // get the topic and partition from the cursor
    val (topic, decoder_?) = {
      if (topic_?.isDefined) (topic_?.get, avro_?)
      else cursor map (c => (c.topic, if (avro_?.isDefined) avro_? else c.decoder)) getOrElse dieNoCursor
    }

    // get the criteria
    val conditions = parseCondition(params, decoder_?)

    // perform the search
    KafkaMicroConsumer.findOne(topic, brokers, correlationId, conditions: _*) map {
      _ map { case (partition, md) =>
        getMessage(topic, partition, md.offset, params)
      }
    }
  }

  /**
   * Returns the first next message that corresponds to the given criteria starting from the current position
   * within the current partition.
   * @example kfindnext volume > 1000000
   * @example kfindnext volume > 1000000 -a file:avro/quotes.avsc
   * @example kfindnext volume > 1000000 -t shocktrade.quotes.avro -p 5 -a file:avro/quotes.avsc
   */
  def findNextMessage(params: UnixLikeArgs)(implicit rt: TxRuntimeContext) = {
    // was a topic, partition and/or Avro decoder specified?
    val topic_? = params("-t")
    val partition_? = params("-p") map parsePartition
    val avro_? = getAvroDecoder(params)(config)

    // get the topic and partition from the cursor
    val (topic, partition, decoder_?) = {
      cursor.map(c => (topic_? getOrElse c.topic, partition_? getOrElse c.partition, avro_?))
        .getOrElse {
        topic_?.map(t => (t, partition_? getOrElse 0, avro_?)) getOrElse dieNoCursor
      }
    }

    // get the criteria
    val conditions = parseCondition(params, decoder_?)

    // perform the search
    KafkaMicroConsumer.findNext(TopicAndPartition(topic, partition), brokers, correlationId, conditions: _*) map {
      _ map (md => getMessage(topic, partition, md.offset, params))
    }
  }

  /**
   * Finds messages that corresponds to the given criteria and exports them to a topic
   * @example kfind frequency > 5000 -o topic:highFrequency.quotes
   * @example kfind -t shocktrade.quotes.avro -a file:avro/quotes.avsc volume > 1000000 -o topic:hft.shocktrade.quotes.avro
   */
  def findMessages(params: UnixLikeArgs)(implicit rt: TxRuntimeContext): Future[Long] = {
    import com.ldaniels528.trifecta.support.messaging.logic.ConditionCompiler._

    // was a topic and/or Avro decoder specified?
    val topic_? = params("-t")
    val avro_? = getAvroDecoder(params)(config)

    // get the input topic and decoder from the cursor
    val (topic, decoder_?) = {
      if (topic_?.isDefined) (topic_?.get, avro_?)
      else cursor map (c => (c.topic, if (avro_?.isDefined) avro_? else c.decoder)) getOrElse dieNoCursor
    }

    // get the criteria
    val conditions = params.args match {
      case field :: operator :: value :: Nil => Seq(compile(compile(field, operator, value), decoder_?))
      case _ => dieSyntax(params)
    }

    // get the output handler
    val outputHandler = params("-o") flatMap rt.getOutputHandler getOrElse die("Output source URL expected")

    // find the messages
    facade.findMessages(topic, decoder_?, conditions, outputHandler)
  }

  /**
   * Retrieves the list of Kafka brokers
   */
  def getBrokers(args: UnixLikeArgs): Seq[BrokerDetails] = {
    KafkaMicroConsumer.getBrokerList
  }

  /**
   * Retrieves the list of Kafka consumers
   * @example kconsumers
   * @example kconsumers -c devGroup
   * @example kconsumers -t shocktrade.keystats.avro
   */
  def getConsumers(params: UnixLikeArgs): Future[List[ConsumerDelta]] = {
    // get the topic & consumer prefixes
    val consumerPrefix = params("-c")
    val topicPrefix = params("-t")

    // get the Kafka consumer groups
    facade.getConsumers(consumerPrefix, topicPrefix)
  }

  /**
   * Displays the current message cursor
   * @example kcursor
   * @example kcursor shocktrade.keystats.avro
   */
  def getCursor(params: UnixLikeArgs): Seq[KafkaCursor] = {
    // get the topic & consumer prefixes
    val topicPrefix = params("-t")

    // filter the cursors by topic prefix
    cursors.values.filter(c => contentFilter(topicPrefix, c.topic)).toSeq
  }

  /**
   * Sets the cursor
   */
  private def setCursor(topic: String, partition: Int, messageData: Option[MessageData], decoder: Option[MessageDecoder[_]]) {
    messageData map (m => KafkaCursor(topic, partition, m.offset, m.nextOffset, decoder)) foreach (cursors(topic) = _)
    currentTopic = Option(topic)
  }

  /**
   * Switches between topic cursors
   * @example kswitch shocktrade.keystats.avro
   */
  def switchCursor(params: UnixLikeArgs) {
    for {
      topic <- params.args.headOption
      cursor <- cursors.get(topic)
    } {
      currentTopic = Option(topic)
    }
  }

  /**
   * Retrieves the fetch size (-s) from the given parameters
   * @param params the given Unix-style parameters
   * @return the fetch size
   */
  private def getFetchSize(params: UnixLikeArgs): Int = {
    params("-s") map (parseInt("fetchSize", _)) getOrElse defaultFetchSize
  }

  /**
   * Returns the first message for a given topic
   * @example kfirst
   * @example kfirst -p 5
   * @example kfirst com.shocktrade.quotes.csv 0
   */
  def getFirstMessage(params: UnixLikeArgs)(implicit rt: TxRuntimeContext) = {
    // get the arguments
    val (topic, partition0) = extractTopicAndPartition(params.args)

    // check for a partition override flag
    val partition: Int = params("-p") map parsePartition getOrElse partition0

    // return the first message for the topic partition
    facade.getFirstOffset(topic, partition) map (getMessage(topic, partition, _, params))
  }

  /**
   * Returns the last offset for a given topic
   * @example klast
   * @example klast -p 5
   * @example klast com.shocktrade.alerts 0
   */
  def getLastMessage(params: UnixLikeArgs)(implicit rt: TxRuntimeContext) = {
    // get the arguments
    val (topic, partition0) = extractTopicAndPartition(params.args)

    // check for a partition override flag
    val partition: Int = params("-p") map parsePartition getOrElse partition0

    // return the last message for the topic partition
    facade.getLastOffset(topic, partition) map (getMessage(topic, partition, _, params))
  }

  /**
   * Returns the message for a given topic partition and offset
   * @example kget 3456
   * @example kget com.shocktrade.alerts 0 3456
   * @example kget -o es:/quotes/quote/AAPL
   */
  def getMessage(params: UnixLikeArgs)(implicit rt: TxRuntimeContext): Either[Option[MessageData], Either[Option[GenericRecord], Option[JValue]]] = {
    // get the arguments
    val (topic, partition0, offset) = extractTopicPartitionAndOffset(params.args)

    // check for a partition override flag
    val partition: Int = params("-p") map parsePartition getOrElse partition0

    // generate and return the message
    getMessage(topic, partition, offset, params)
  }

  /**
   * Retrieves either a binary or decoded message
   * @param topic the given topic
   * @param partition the given partition
   * @param offset the given offset
   * @param params the given Unix-style argument
   * @return either a binary or decoded message
   */
  def getMessage(topic: String, partition: Int, offset: Long, params: UnixLikeArgs)(implicit rt: TxRuntimeContext): Either[Option[MessageData], Either[Option[GenericRecord], Option[JValue]]] = {
    // requesting a message from an instance in time?
    val instant: Option[Long] = params("-ts") map {
      case s if s.matches("\\d+") => s.toLong
      case s if s.matches("\\d{4}[-]\\d{2}-\\d{2}[T]\\d{2}[:]\\d{2}[:]\\d{2}") => new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(s).getTime
      case s => throw die(s"Illegal timestamp format '$s' - expected either EPOC (Long) or yyyy-MM-dd'T'HH:mm:ss format")
    }

    // retrieve the message
    val messageData = new KafkaMicroConsumer(TopicAndPartition(topic, partition), brokers, correlationId) use { consumer =>
      val myOffset: Long = instant flatMap (t => consumer.getOffsetsBefore(t).headOption) getOrElse offset
      consumer.fetch(myOffset, getFetchSize(params)).headOption
    }

    // determine which decoder to use; either the user specified decoder, cursor's decoder or none
    val decoder: Option[MessageDecoder[_]] = Seq(params("-a") map (lookupAvroDecoder(_)(config)), cursors.get(topic)
      .flatMap(_.decoder)).find(_.isDefined).flatten

    // if a decoder was found, use it to decode the message
    val decodedMessage = decoder.flatMap(decodeMessage(messageData, _))

    // write the message to an output source handler
    messageData.foreach { md =>
      val outputSource = getOutputSource(params)
      outputSource.foreach(_.write(KeyAndMessage(md.key, md.message), decoder))
    }

    // was a format parameter specified?
    val jsonMessage = for {
      format <- params("-f")
      message <- decodedMessage if format == "json"
      jsonMessage = net.liftweb.json.parse(message.toString)
    } yield jsonMessage

    // capture the message's offset and decoder
    setCursor(topic, partition, messageData, decoder)

    // return either a binary message or a decoded message
    if (jsonMessage.isDefined) Right(Right(jsonMessage))
    else if (decodedMessage.isDefined) Right(Left(decodedMessage))
    else Left(messageData)
  }

  /**
   * Decodes the given message
   * @param messageData the given option of a message
   * @param aDecoder the given message decoder
   * @return the decoded message
   */
  private def decodeMessage(messageData: Option[MessageData], aDecoder: MessageDecoder[_]): Option[GenericRecord] = {
    // only Avro decoders are supported
    val decoder: AvroDecoder = aDecoder match {
      case avDecoder: AvroDecoder => avDecoder
      case _ => throw new IllegalStateException("Only Avro decoding is supported")
    }

    // decode the message
    for {
      md <- messageData
      rec = decoder.decode(md.message) match {
        case Success(record) => record
        case Failure(e) =>
          throw new IllegalStateException(e.getMessage, e)
      }
    } yield rec
  }

  /**
   * Returns the message key for a given topic partition and offset
   * @example kget com.shocktrade.alerts 0 3456
   * @example kget 3456
   */
  def getMessageKey(params: UnixLikeArgs): Option[Any] = {
    // get the arguments
    val (topic, partition, offset) = extractTopicPartitionAndOffset(params.args)

    // retrieve (or guess) the value's format
    val valueType = params("-f") getOrElse "bytes"

    // retrieve the key
    facade.getMessageKey(topic, partition, offset, getFetchSize(params)) map (decodeValue(_, valueType))
  }

  /**
   * Returns the size of the message for a given topic partition and offset
   * @example kgetsize com.shocktrade.alerts 0 5567
   * @example kgetsize 5567
   */
  def getMessageSize(params: UnixLikeArgs): Option[Int] = {
    // get the arguments (topic, partition, groupId and offset)
    val (topic, partition, offset) = extractTopicPartitionAndOffset(params.args)

    // perform the action
    facade.getMessageSize(topic, partition, offset, getFetchSize(params))
  }

  /**
   * Returns the minimum and maximum message size for a given topic partition and offset range
   * @example kgetmaxsize com.shocktrade.alerts 0 2100 5567
   * @example kgetmaxsize 2100 5567
   */
  def getMessageMinMaxSize(params: UnixLikeArgs): Seq[MessageMaxMin] = {
    // get the arguments (topic, partition, startOffset and endOffset)
    val (topic, partition, startOffset, endOffset) = params.args match {
      case offset0 :: offset1 :: Nil => cursor map (c => (c.topic, c.partition, parseOffset(offset0), parseOffset(offset1))) getOrElse dieNoCursor
      case aTopic :: aPartition :: aStartOffset :: anEndOffset :: Nil => (aTopic, parsePartition(aPartition), parseOffset(aStartOffset), parseOffset(anEndOffset))
      case _ => dieSyntax(params)
    }

    // perform the action
    facade.getMessageMinMaxSize(topic, partition, startOffset, endOffset, getFetchSize(params))
  }

  /**
   * Optionally returns the next message
   * @example knext
   * @example knext +10
   */
  def getNextMessage(params: UnixLikeArgs)(implicit rt: TxRuntimeContext) = {
    cursor map { case KafkaCursor(topic, partition, offset, nextOffset, decoder) =>
      val delta = params.args.headOption map (parseDelta("position delta", _))
      val theOffset = delta map (nextOffset + _) getOrElse nextOffset
      val lastOffset = facade.getLastOffset(topic, partition)
      if (lastOffset.exists(theOffset > _)) {
        for {
          (min, max) <- facade.getTopicPartitionRange(topic)
          overflowPartition = (partition + 1) % (max + 1)
          overflowOffset <- facade.getFirstOffset(topic, overflowPartition)
        } yield getMessage(topic, overflowPartition, overflowOffset, params)
      }
      else getMessage(topic, partition, theOffset, params)
    }
  }

  /**
   * Optionally returns the previous message
   * @example kprev
   * @example kprev +10
   */
  def getPreviousMessage(params: UnixLikeArgs)(implicit rt: TxRuntimeContext) = {
    cursor map { case KafkaCursor(topic, partition, offset, nextOffset, decoder) =>
      val delta = params.args.headOption map (parseDelta("position delta", _))
      val theOffset = Math.max(0, delta map (offset - _) getOrElse (offset - 1))
      val firstOffset = facade.getFirstOffset(topic, partition)
      if (firstOffset.exists(theOffset < _)) {
        for {
          (min, max) <- facade.getTopicPartitionRange(topic)
          overflowPartition = if(partition <= min) max else (partition - 1) % (max + 1)
          overflowOffset <- facade.getLastOffset(topic, overflowPartition)
        } yield getMessage(topic, overflowPartition, overflowOffset, params)
      }
      else getMessage(topic, partition, theOffset, params)
    }
  }

  /**
   * Returns the number of available messages for a given topic
   * @example kstats com.shocktrade.alerts 0 4
   * @example kstats com.shocktrade.alerts
   * @example kstats
   */
  def getStatistics(params: UnixLikeArgs): Iterable[TopicOffsets] = {
    // interpret based on the input arguments
    val results = params.args match {
      case Nil =>
        val topic = cursor map (_.topic) getOrElse dieNoCursor
        val partitions = KafkaMicroConsumer.getTopicList(brokers, correlationId).filter(_.topic == topic).map(_.partitionId)
        if (partitions.nonEmpty) Some((topic, partitions.min, partitions.max)) else None

      case topic :: Nil =>
        val partitions = KafkaMicroConsumer.getTopicList(brokers, correlationId).filter(_.topic == topic).map(_.partitionId)
        if (partitions.nonEmpty) Some((topic, partitions.min, partitions.max)) else None

      case topic :: aPartition :: Nil =>
        Some((topic, parsePartition(aPartition), parsePartition(aPartition)))

      case topic :: partitionA :: partitionB :: Nil =>
        Some((topic, parsePartition(partitionA), parsePartition(partitionB)))

      case _ =>
        dieSyntax(params)
    }

    results match {
      case Some((topic, partition0, partition1)) =>
        if (cursor.isEmpty) {
          facade.getFirstOffset(topic, partition0) ?? facade.getLastOffset(topic, partition0) map (offset =>
            KafkaCursor(topic, partition0, offset, offset + 1, None)) foreach (cursors(topic) = _)
          currentTopic = Option(topic)
        }
        facade.getStatisticsData(topic, partition0, partition1)
      case _ => Nil
    }
  }

  /**
   * Returns a list of topics
   * @example kls com.shocktrade.alerts
   * @example kls
   */
  def getTopics(params: UnixLikeArgs): Either[Seq[TopicItem], Seq[TopicItemCompact]] = {
    // get the prefix and compact/detailed list indicator
    val prefix = params("-l") ?? params.args.headOption
    val detailed = params.contains("-l")

    // get the raw topic data
    facade.getTopics(prefix, detailed)
  }

  /**
   * Retrieves a list of all topics with new messages (since last query)
   * @example kinbound com.shocktrade.quotes
   */
  def inboundMessages(params: UnixLikeArgs): Iterable[Inbound] = {
    val prefix = params.args.headOption

    // get the optional wait time parameter
    val waitTime = params("-w") map (parseInt("wait time in seconds", _))

    // is this the initial call to this command?
    if (waitTime.isDefined || incomingMessageCache.isEmpty || (System.currentTimeMillis() - lastInboundCheck) >= 30.minutes) {
      out.println("Sampling data; this may take a few seconds...")

      // generate some data to fill the cache
      inboundMessageStatistics()

      // wait for the specified time in second
      Thread.sleep((waitTime getOrElse 3).seconds)
    }

    // capture the current time
    lastInboundCheck = System.currentTimeMillis()

    // get the inbound topic data
    inboundMessageStatistics(prefix)
  }

  /**
   * Generates an iteration of inbound message statistics
   * @param topicPrefix the given topic prefix (e.g. "myTopic123")
   * @return an iteration of inbound message statistics
   */
  private def inboundMessageStatistics(topicPrefix: Option[String] = None): Iterable[Inbound] = {
    // start by retrieving a list of all topics
    val topics = KafkaMicroConsumer.getTopicList(brokers, correlationId)
      .filter(t => t.topic == topicPrefix.getOrElse(t.topic))
      .groupBy(_.topic)

    // generate the inbound data
    val inboundData = (topics flatMap { case (topic, details) =>
      // get the range of partitions for each topic
      val partitions = details.map(_.partitionId)
      val (beginPartition, endPartition) = (partitions.min, partitions.max)

      // retrieve the statistics for each topic
      facade.getStatisticsData(topic, beginPartition, endPartition) map { o =>
        val prevInbound = incomingMessageCache.get(TopicAndPartition(o.topic, o.partition))
        val lastCheckTime = prevInbound.map(_.lastCheckTime.getTime) getOrElse System.currentTimeMillis()
        val currentTime = System.currentTimeMillis()
        val elapsedTime = 1 + (currentTime - lastCheckTime) / 1000L
        val change = prevInbound map (o.endOffset - _.endOffset) getOrElse 0L
        val rate = BigDecimal(change.toDouble / elapsedTime).setScale(1, BigDecimal.RoundingMode.UP).toDouble
        Inbound(o.topic, o.partition, o.startOffset, o.endOffset, change, rate, new Date(currentTime))
      }
    }).toSeq

    // cache the unfiltered inbound data
    incomingMessageCache = incomingMessageCache ++ Map(inboundData map (i => TopicAndPartition(i.topic, i.partition) -> i): _*)

    // filter out the non-changed records
    inboundData.filterNot(_.change == 0) sortBy (-_.change)
  }

  /**
   * Publishes the given message to a given topic
   * @example kput greetings a0.00.11.22.33.44.ef.11 "Hello World"
   * @example kput a0.00.11.22.33.44.ef.11 "Hello World" (references cursor)
   */
  def publishMessage(params: UnixLikeArgs): Unit = {
    import com.ldaniels528.trifecta.command.CommandParser._

    // get the topic, key and message
    val (topic, key, message) = params.args match {
      case aKey :: aMessage :: Nil => cursor map (c => (c.topic, aKey, aMessage)) getOrElse dieNoCursor
      case aTopic :: aKey :: aMessage :: Nil => (aTopic, aKey, aMessage)
      case _ => dieSyntax(params)
    }

    // convert the key and message to binary
    val keyBytes = if (isDottedHex(key)) parseDottedHex(key) else key.getBytes(config.encoding)
    val msgBytes = if (isDottedHex(message)) parseDottedHex(message) else message.getBytes(config.encoding)

    // publish the message
    facade.publishMessage(topic, keyBytes, msgBytes)
  }

  /**
   * Sets the offset of a consumer group ID to zero for all partitions
   * @example kreset com.shocktrade.quotes.csv lld
   */
  def resetConsumerGroup(params: UnixLikeArgs): Unit = {
    // get the arguments
    val (topic, groupId) = params.args match {
      case aGroupId :: Nil => cursor map (c => (c.topic, aGroupId)) getOrElse dieNoCursor
      case aTopic :: aGroupId :: Nil => (aTopic, aGroupId)
      case _ => dieSyntax(params)
    }

    // get the partition range
    facade.resetConsumerGroup(topic, groupId)
  }

  private def dieNoCursor[S](): S = die("No topic/partition specified and no cursor exists")

  private def dieNoInputSource[S](): S = die("No input source specified")

  /**
   * Retrieves the topic and partition from the given arguments
   * @param args the given arguments
   * @return a tuple containing the topic and partition
   */
  private def extractTopicAndPartition(args: List[String]): (String, Int) = {
    args match {
      case Nil => cursor map (c => (c.topic, c.partition)) getOrElse dieNoCursor
      case aTopic :: Nil => (aTopic, 0)
      case aTopic :: aPartition :: Nil => (aTopic, parsePartition(aPartition))
      case _ => die("Invalid arguments")
    }
  }

  /**
   * Retrieves the topic, partition and offset from the given arguments
   * @param args the given arguments
   * @return a tuple containing the topic, partition and offset
   */
  private def extractTopicPartitionAndOffset(args: List[String]): (String, Int, Long) = {
    args match {
      case Nil => cursor map (c => (c.topic, c.partition, c.offset)) getOrElse dieNoCursor
      case anOffset :: Nil => cursor map (c => (c.topic, c.partition, parseOffset(anOffset))) getOrElse dieNoCursor
      case aTopic :: aPartition :: anOffset :: Nil => (aTopic, parsePartition(aPartition), parseOffset(anOffset))
      case _ => die("Invalid arguments")
    }
  }

  /**
   * Parses a condition statement
   * @param params the given [[UnixLikeArgs]]
   * @param decoder the optional [[MessageDecoder]]
   * @example lastTrade < 1 and volume > 1000000
   * @return a collection of [[Condition]] objects
   */
  private def parseCondition(params: UnixLikeArgs, decoder: Option[MessageDecoder[_]]): Seq[Condition] = {
    import com.ldaniels528.trifecta.support.messaging.logic.ConditionCompiler._

    val conditions = mutable.Buffer[Condition]()
    val it = params.args.iterator
    while (it.hasNext) {
      it.take(3).toList match {
        case List(field, operator, value) =>
          conditions += compile(compile(field, operator, value), decoder)
        case arg =>
          throw new IllegalArgumentException(s"Invalid expression near $arg")
      }
      if (it.hasNext) {
        it.next() match {
          case "and" =>
          case arg => new IllegalArgumentException("Invalid expression near $arg")
        }
      }
    }
    conditions
  }

  /**
   * Returns the connected Zookeeper Proxy
   * @return the connected Zookeeper Proxy
   */
  private implicit def zk: ZKProxy = {
    zkProxy_? match {
      case Some(zk) => zk
      case None =>
        val zk = ZKProxy(config.kafkaZkConnect)
        zkProxy_? = Option(zk)
        zk
    }
  }

}
