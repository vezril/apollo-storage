package apollostorage.persistence

import apollostorage.domain.Event
import com.fasterxml.jackson.annotation.{JsonSubTypes, JsonTypeInfo}

/**
 * Jackson mix-in that adds polymorphic type information to the pure `Event` ADT without putting
 * annotations in `core`. Applied via `setMixInAnnotations` in [[DomainJacksonModule]]. The `_type`
 * names are the stable on-disk schema contract (design D4) — never rename them.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "_type")
@JsonSubTypes(
  Array(
    new JsonSubTypes.Type(value = classOf[Event.BucketCreated], name = "BucketCreated"),
    new JsonSubTypes.Type(value = classOf[Event.BucketDeleted], name = "BucketDeleted"),
    new JsonSubTypes.Type(value = classOf[Event.ObjectCommitted], name = "ObjectCommitted"),
    new JsonSubTypes.Type(value = classOf[Event.ObjectDeleted], name = "ObjectDeleted")
  )
)
private[persistence] trait EventMixin
