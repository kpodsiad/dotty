package dotty.tools.dotc
package transform

import core._
import Contexts.Context
import Decorators._
import pickling._
import config.Printers.{noPrinter, pickling}
import java.io.PrintStream
import Periods._
import Phases._
import collection.mutable

/** This miniphase pickles trees */
class Pickler extends Phase {
  import ast.tpd._

  override def phaseName: String = "pickler"
  
  private def output(name: String, msg: String) = {
    val s = new PrintStream(name)
    s.print(msg)
    s.close
  }
  
  private val beforePickling = new mutable.HashMap[CompilationUnit, String]
  
  override def run(implicit ctx: Context): Unit = {
    val unit = ctx.compilationUnit
    if (!unit.isJava) {
      val tree = unit.tpdTree
      pickling.println(i"unpickling in run ${ctx.runId}")
      if (ctx.settings.YtestPickler.value) beforePickling(unit) = tree.show

      val pickler = new TastyPickler            
      val treePkl = new TreePickler(pickler)
      treePkl.pickle(tree :: Nil)
      if (tree.pos.exists)
        new PositionPickler(pickler, treePkl.buf.addrOfTree).picklePositions(tree :: Nil, tree.pos)

      unit.pickled = pickler.assembleParts()
      def rawBytes = // not needed right now, but useful to print raw format.
        unit.pickled.iterator.grouped(10).toList.zipWithIndex.map {
          case (row, i) => s"${i}0: ${row.mkString(" ")}"
        }
      // println(i"rawBytes = \n$rawBytes%\n%") // DEBUG
      if (pickling ne noPrinter) new TastyPrinter(unit.pickled).printContents()
    } 
  }
  
  override def runOn(units: List[CompilationUnit])(implicit ctx: Context): List[CompilationUnit] = {
    val result = super.runOn(units)
    if (ctx.settings.YtestPickler.value) 
      testUnpickler(units)(ctx.fresh.setPeriod(Period(ctx.runId + 1, FirstPhaseId)))
    result
  }
  
  private def testUnpickler(units: List[CompilationUnit])(implicit ctx: Context): Unit = {
    println(i"testing unpickler at run ${ctx.runId}")
    ctx.definitions.init
    for (unit <- units) {
      unpickle(unit.pickled, beforePickling(unit))
    }
  }
  
  private def unpickle(bytes: Array[Byte], previous: String)(implicit ctx: Context) = {
    val unpickled = i"${new DottyUnpickler(bytes, Set(), readPositions = false).result}%\n%"
    println(i"previous :\n $previous")
    println(i"unpickled:\n $unpickled")
    if (previous != unpickled) {
      output("before-pickling.txt", previous)
      output("after-pickling.txt", unpickled)
      println(s"""pickling difference for ${ctx.compilationUnit}, for details:
                 |
                 |  diff before-pickling.txt after-pickling.txt""".stripMargin)
    }
  }
}