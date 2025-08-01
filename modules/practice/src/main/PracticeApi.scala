package lila.practice

import lila.common.Bus
import lila.db.dsl.{ *, given }
import lila.memo.CacheApi.*
import lila.study.{ ChapterPreview, Study }

final class PracticeApi(
    coll: Coll,
    configStore: ConfigStore[PracticeConfig],
    cacheApi: lila.memo.CacheApi,
    studyApi: lila.study.StudyApi
)(using Executor):

  import BSONHandlers.given

  def get(user: Option[User]): Fu[UserPractice] = for
    struct <- structure.get
    prog <- user.fold(fuccess(PracticeProgress.anon))(progress.get)
  yield UserPractice(struct, prog)

  def getStudyWithFirstOngoingChapter(user: Option[User], studyId: StudyId): Fu[Option[UserStudy]] = for
    up <- get(user)
    chapters <- studyApi.chapterPreviews(studyId)
    chapter = up.progress.firstOngoingIn(chapters)
    studyOption <- chapter.fold(studyApi.byIdWithFirstChapter(studyId)) { chapter =>
      studyApi.byIdWithChapterOrFallback(studyId, chapter.id)
    }
  yield makeUserStudy(studyOption, up, chapters)

  def getStudyWithChapter(
      user: Option[User],
      studyId: StudyId,
      chapterId: StudyChapterId
  ): Fu[Option[UserStudy]] = for
    up <- get(user)
    chapters <- studyApi.chapterPreviews(studyId)
    studyOption <- studyApi.byIdWithChapterOrFallback(studyId, chapterId)
  yield makeUserStudy(studyOption, up, chapters)

  private def makeUserStudy(
      studyOption: Option[Study.WithChapter],
      up: UserPractice,
      chapters: List[ChapterPreview]
  ) = for
    rawSc <- studyOption
    sc = rawSc.copy(
      study = rawSc.study.rewindTo(rawSc.chapter.id).withoutMembers,
      chapter = rawSc.chapter.withoutChildrenIfPractice
    )
    practiceStudy <- up.structure.study(sc.study.id)
    section <- up.structure.findSection(sc.study.id)
    publishedChapters = chapters.filterNot: c =>
      PracticeStructure.isChapterNameCommented(c.name)
    if publishedChapters.exists(_.id == sc.chapter.id)
    previews =
      import ChapterPreview.json.given
      import play.api.libs.json.Json
      Json.toJson(publishedChapters)
  yield UserStudy(up, practiceStudy, previews, sc, section)

  object config:
    def get = configStore.get.dmap(_ | PracticeConfig.empty)
    def set = configStore.set
    def form = configStore.makeForm

  object structure:
    private val cache = cacheApi.unit[PracticeStructure]:
      _.expireAfterAccess(3.hours).buildAsyncFuture: _ =>
        for
          conf <- config.get
          chapters <- studyApi.chapterIdNames(conf.studyIds)
        yield PracticeStructure.make(conf, chapters)
    def get = cache.getUnit
    def clear() = cache.invalidateUnit()

    val getStudies: lila.core.practice.GetStudies = () => get.map(_.study)

  object progress:

    lila.common.Bus.sub[lila.core.user.UserDelete]: del =>
      coll.delete.one($id(del.id)).void

    import PracticeProgress.NbMoves

    def get(user: User): Fu[PracticeProgress] =
      coll.one[PracticeProgress]($id(user.id)).dmap(_ | PracticeProgress.empty(user.id))

    private def save(p: PracticeProgress): Funit =
      coll.update.one($id(p.id), p, upsert = true).void

    def setNbMoves(user: User, chapterId: StudyChapterId, score: NbMoves): Funit = for
      prog <- get(user)
      _ <- save(prog.withNbMoves(chapterId, score))
      studyId <- studyApi.studyIdOf(chapterId)
    yield studyId.so: studyId =>
      Bus.pub(lila.core.practice.OnComplete(user.id, studyId, chapterId))

    def reset(user: User) =
      coll.delete.one($id(user.id)).void

    def completionPercent(userIds: List[UserId]): Fu[Map[UserId, Int]] =
      coll
        .aggregateList(Int.MaxValue, _.sec): framework =>
          import framework.*
          Match($doc("_id".$in(userIds))) -> List(
            Project(
              $doc(
                "nb" -> $doc(
                  "$size" -> $doc(
                    "$objectToArray" -> "$chapters"
                  )
                )
              )
            )
          )
        .map:
          _.view
            .flatMap: obj =>
              (obj.getAsOpt[UserId]("_id"), obj.int("nb")).mapN: (k, v) =>
                k -> (v * 100f / PracticeStructure.totalChapters).toInt
            .toMap
