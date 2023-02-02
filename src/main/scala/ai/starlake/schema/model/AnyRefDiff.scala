package ai.starlake.schema.model

import java.lang.reflect.Modifier

object AnyRefDiff {
  def extractFieldValues(obj: AnyRef): List[NamedValue] = {
    val cls = obj.getClass
    val fields = cls
      .getDeclaredFields()
      .filter(field => !Modifier.isTransient(field.getModifiers))
      .filter(_.getDeclaredAnnotations.isEmpty)
    val fieldNames = fields.map(_.getName)
    cls.getDeclaredMethods.flatMap { method =>
      if (
        fieldNames.contains(method.getName) &&
        Modifier.isPublic(method.getModifiers()) && method.getParameterCount == 0 && !method.getName
          .contains('$')
      ) {
        Some(NamedValue(method.getName, method.invoke(obj)))
      } else None
    }.toList
  }

  def diffNamed(
    fieldName: String,
    existing: List[Named],
    incoming: List[Named]
  ): ListDiff[Named] = {
    val set1 = existing.toSet
    val set2 = incoming.toSet
    val deleted = Named.diff(set1, set2)
    val added = Named.diff(set2, set1)
    val common1 = Named.diff(set1, deleted).toList.sortBy(_.name)
    val common2 = Named.diff(set2, added).toList.sortBy(_.name)

    val common = common1.zip(common2).map { case (v1, v2) =>
      assert(v1.name == v2.name)
      (v1.name, v1, v2)
    }
    val updated = common.flatMap { case (k, v1, v2) =>
      if (v1 != v2)
        // diffAny(k, v1, v2).updated
        Some(v1 -> v2)
      else
        None

    }
    ListDiff(fieldName, added.toList, deleted.toList, updated)
  }

  def diffMap(
    fieldName: String,
    existing: Map[String, String],
    incoming: Map[String, String]
  ): ListDiff[Named] = {
    val existingNamed = existing.map { case (k, v) => NamedValue(k, v) }.toList
    val incomingNamed = incoming.map { case (k, v) => NamedValue(k, v) }.toList
    diffNamed(fieldName, existingNamed, incomingNamed)
  }

  def diffAny(
    fieldName: String,
    existing: AnyRef,
    incoming: AnyRef
  ): ListDiff[Named] = {
    val existingFields = extractFieldValues(existing)
    val incomingFields = extractFieldValues(incoming)
    diffNamed(fieldName, existingFields, incomingFields)
  }

  def diffAny(
    fieldName: String,
    existing: Option[AnyRef],
    incoming: Option[AnyRef]
  ): ListDiff[Named] = {
    (existing, incoming) match {
      case (Some(existing), Some(incoming)) =>
        diffAny(fieldName, existing, incoming)
      case (None, Some(incoming)) =>
        val incomingFields = extractFieldValues(incoming)
        diffNamed(fieldName, Nil, incomingFields)
      case (Some(existing), None) =>
        val existingFields = extractFieldValues(existing)
        diffNamed(fieldName, Nil, existingFields)
      case (None, None) =>
        diffNamed(fieldName, Nil, Nil)
    }
  }

  def diffString(
    fieldName: String,
    existing: Option[String],
    incoming: Option[String]
  ): ListDiff[String] = {
    diffString(fieldName, existing.toSet, incoming.toSet)
  }

  def diffString(
    fieldName: String,
    existing: Set[String],
    incoming: Set[String]
  ): ListDiff[String] = {
    val deleted = existing.diff(incoming)
    val added = incoming.diff(existing)
    ListDiff(fieldName, added.toList, deleted.toList, Nil)
  }
}

case class ListDiff[T](
  field: String,
  added: List[T],
  deleted: List[T],
  updated: List[(T, T)]
)