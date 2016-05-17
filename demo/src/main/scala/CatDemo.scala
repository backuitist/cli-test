import java.io.File

import org.backuity.clist._

object CatDemo {

  class Cat extends Command(description = "concatenate files and print on the standard output") {

    var showAll        = opt[Boolean](abbrev = "A", description = "equivalent to -vET")
    var numberNonblank = opt[Boolean](abbrev = "b", description = "number nonempty output lines, overrides -n")

    var maxLines       = opt[Int](default = 123)
    var nums           = opt[Seq[Int]](default = Seq.empty, description = "a list of numbers")

    var files          = args[Seq[File]](description = "files to concat")
  }

  def main(args: Array[String]) {
    Cli.parse(args).withCommand(new Cat) { case cat =>
      println("nums     = " + cat.nums)
      println("files    = " + cat.files)
      println("showAll  = " + cat.showAll)
      println("maxLines = " + cat.maxLines)
    }
  }
}
