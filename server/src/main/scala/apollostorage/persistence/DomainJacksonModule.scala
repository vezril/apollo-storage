package apollostorage.persistence

import apollostorage.domain.{BlobRef, BucketName, Event, Generation, ObjectName}
import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.Module.SetupContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.databind.{DeserializationContext, KeyDeserializer, SerializerProvider}

/**
 * Teaches Pekko's Jackson ObjectMapper how to (de)serialize the pure-domain value types as scalars
 * and how to handle the polymorphic `Event` ADT (via a mix-in). Registered through config:
 * `pekko.serialization.jackson.jackson-modules`.
 *
 * Value types have private constructors; deserialization uses the trusted `unsafe` rehydration
 * factories since the values were validated before they were ever persisted.
 */
final class DomainJacksonModule extends SimpleModule("ApolloDomainModule"):

  // --- BucketName <-> JSON string ---
  addSerializer(
    classOf[BucketName],
    new StdSerializer[BucketName](classOf[BucketName]):
      def serialize(v: BucketName, gen: JsonGenerator, p: SerializerProvider): Unit =
        gen.writeString(v.value)
  )
  addDeserializer(
    classOf[BucketName],
    new StdDeserializer[BucketName](classOf[BucketName]):
      def deserialize(p: JsonParser, ctx: DeserializationContext): BucketName =
        BucketName.unsafe(p.getValueAsString)
  )

  // --- ObjectName <-> JSON string (value and map key) ---
  addSerializer(
    classOf[ObjectName],
    new StdSerializer[ObjectName](classOf[ObjectName]):
      def serialize(v: ObjectName, gen: JsonGenerator, p: SerializerProvider): Unit =
        gen.writeString(v.value)
  )
  addDeserializer(
    classOf[ObjectName],
    new StdDeserializer[ObjectName](classOf[ObjectName]):
      def deserialize(p: JsonParser, ctx: DeserializationContext): ObjectName =
        ObjectName.unsafe(p.getValueAsString)
  )
  addKeySerializer(
    classOf[ObjectName],
    new StdSerializer[ObjectName](classOf[ObjectName]):
      def serialize(v: ObjectName, gen: JsonGenerator, p: SerializerProvider): Unit =
        gen.writeFieldName(v.value)
  )
  addKeyDeserializer(
    classOf[ObjectName],
    new KeyDeserializer:
      def deserializeKey(key: String, ctx: DeserializationContext): AnyRef =
        ObjectName.unsafe(key)
  )

  // --- Generation <-> JSON number ---
  addSerializer(
    classOf[Generation],
    new StdSerializer[Generation](classOf[Generation]):
      def serialize(v: Generation, gen: JsonGenerator, p: SerializerProvider): Unit =
        gen.writeNumber(v.value)
  )
  addDeserializer(
    classOf[Generation],
    new StdDeserializer[Generation](classOf[Generation]):
      def deserialize(p: JsonParser, ctx: DeserializationContext): Generation =
        Generation.unsafe(p.getLongValue)
  )

  // --- BlobRef <-> JSON string ---
  addSerializer(
    classOf[BlobRef],
    new StdSerializer[BlobRef](classOf[BlobRef]):
      def serialize(v: BlobRef, gen: JsonGenerator, p: SerializerProvider): Unit =
        gen.writeString(v.value)
  )
  addDeserializer(
    classOf[BlobRef],
    new StdDeserializer[BlobRef](classOf[BlobRef]):
      def deserialize(p: JsonParser, ctx: DeserializationContext): BlobRef =
        BlobRef(p.getValueAsString)
  )

  override def setupModule(context: SetupContext): Unit =
    super.setupModule(context)
    context.setMixInAnnotations(classOf[Event], classOf[EventMixin])
