package org.stingray.contester.polygon

import org.apache.http.client.utils.URIUtils

trait ContestResolver {
  def getContestURI(pcid: PolygonContestId): Option[PolygonContest]
}

case class PolygonContestResolver(polygons: Map[String, PolygonConfig]) extends ContestResolver {
  def getContestURI(pcid: PolygonContestId): Option[PolygonContest] =
    polygons.get(pcid.polygon).map { p =>
      PolygonContest(URIUtils.resolve(p.uri.head, s"c/${pcid.contestId}/contest.xml"))
    }
}

object PolygonContestId {
  private def parseSource(source: String): (String, Int) = {
    val splits = source.split(':')
    if (splits.length == 1)
      ("default", splits(0).toInt)
    else
      (splits(0), splits(1).toInt)
  }

  def apply(source: String): PolygonContestId = {
    val parsed = parseSource(source)
    PolygonContestId(parsed._1, parsed._2)
  }
}

case class PolygonContestId(polygon: String, contestId: Int)
