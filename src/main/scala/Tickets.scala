package org.stingray.contester

import org.stingray.contester.db.ConnectionPool

class DbHelper(db: ConnectionPool) {

  case class Computer(id: Int, location: Int, name: String)
  def getComputer(id: Int) =
    db.select("select * from CompLocations where ID = ?", id) { row =>
      Computer(row.getInt("ID"), row.getInt("Location"), row.getString("Name"))
    }.map(_.headOption)


}

object TicketGrabber {
  val submitDescQuery =
    """
      |select
      |Contest, Team, Touched, Task, Compiled,
      |(unix_timestamp(Arrived) - unix_timestamp(Contests.Start)) as Arrived,
      |Passed, Taken, Areas.Printer, Contests.SchoolMode, TestingID, Schools.Name,
      |Teams.Num, Contests.Name as ContestName, Submits.ID
      |from
      |Submits, Contests, Areas, CompLocations, Teams, Schools, Participants
      |where
      |Contests.ID = Contest, Finished, ((a.Printed is null) or (a.Printed < a.Touched))
    """.stripMargin

}