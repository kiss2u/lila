package lila.relation

import akka.stream.scaladsl.*
import reactivemongo.akkastream.cursorProducer

import lila.db.dsl.{ *, given }
import lila.core.user.UserRepo
import reactivemongo.api.bson.BSONDocumentReader
import lila.core.LightUser

final class RelationStream(colls: Colls, userRepo: UserRepo)(using akka.stream.Materializer):

  private val coll = colls.relation

  def follow(perSecond: MaxPerSecond)(using me: Me): Source[Seq[UserId], ?] =
    coll
      .find(
        $doc(F.from -> me.userId, "r" -> lila.core.relation.Relation.Follow),
        $doc(F.to -> true, "_id" -> false).some
      )
      .batchSize(perSecond.value)
      .cursor[Bdoc](ReadPref.sec)
      .documentSource()
      .grouped(perSecond.value)
      .map(_.flatMap(_.getAsOpt[UserId](F.to)))
      .throttle(1, 1.second)

  def recentlySeen(nb: Int, projection: Bdoc)(using
      reader: BSONDocumentReader[LightUser],
      me: Me
  ): Source[(LightUser, Option[Instant]), ?] =
    coll
      .aggregateWith[Bdoc](readPreference = ReadPref.sec): framework =>
        import framework.*
        List(
          Match($doc(F.from -> me.userId, "r" -> lila.core.relation.Relation.Follow)),
          PipelineOperator(
            $lookup.simple(
              from = userRepo.coll,
              as = "user",
              local = F.to,
              foreign = "_id",
              pipe = List(
                $doc("$match" -> $doc("enabled" -> true)),
                $doc("$sort" -> $doc("seenAt" -> -1)),
                $doc("$project" -> (projection ++ $doc("seenAt" -> true))),
                $doc("$limit" -> nb)
              )
            )
          ),
          ReplaceRootField("user.0")
        )
      .documentSource()
      .mapConcat: doc =>
        for
          user <- doc.asOpt[LightUser]
          seenAt = doc.getAsOpt[Instant]("seenAt")
        yield (user, seenAt)

  private object F:
    val from = "u1"
    val to = "u2"
