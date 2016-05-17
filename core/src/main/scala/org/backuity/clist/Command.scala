package org.backuity.clist

import org.backuity.clist.util.{ReadException, Formatting}
import Formatting.ClassUtil


/** @param name if not specified the lower-cased class name will be used */
abstract class Command(name: String = null, val description: String = "") extends ValidationUtils {

  import Command._

  /** @throws ParsingException if arguments or options cannot be parsed */
  def read(args: List[String]) = {

    parseArguments(
      parseOptions(
        parseNamedArguments(ParseContext(this, args))))

    applyValidators()
  }

  private def parseNamedArguments(ctx: ParseContext): ParseContext = {
    var newCtx = ctx

    for (_cmdArg <- arguments if _cmdArg.isInstanceOf[SingleArgAttribute[_]]) {
      val cmdArg = _cmdArg.asInstanceOf[CliArgument[_] with SingleArgAttribute[_]]
      newCtx.findArgForName(cmdArg.name).foreach { case (arg,value) =>
        readAndSetVar(cmdArg, value)
        newCtx = newCtx.validate(cmdArg, arg)
      }
    }

    newCtx
  }

  private def parseArguments(ctx: ParseContext): Unit = {
    var newCtx = ctx

    for (cmdArg <- newCtx.args) {
      cmdArg match {
        case sCmdArg: SingleCliArgument[_] =>
          newCtx.firstArg match {
            case None =>
              sCmdArg match {
                case _: CliMandatoryArgument[_] =>
                  throw ParsingException("No argument provided for " + cmdArg.name)
                case optArg: CliOptionalArgument[_] =>
                  setVar(cmdArg, optArg.default)
              }

            case Some(arg) =>
              readAndSetVar(sCmdArg, arg)
              newCtx = newCtx.validate(sCmdArg, arg)
          }

        case mult: MultipleCliArgument[_] =>
          if (newCtx.remainingArgs.isEmpty) {
            throw new ParsingException(s"Insufficient arguments for ${mult.name}")
          } else {
            try {
              val value = mult.reader.reads(newCtx.remainingArgs)
              setVar(mult, value)
              newCtx = newCtx.validateAllArgs
            } catch {
              case ReadException(value, expected) =>
                throw new ParsingException(s"Incorrect parameter ${mult.name} '$value', expected $expected")
            }
          }
      }
    }

    if (newCtx.remainingArgs.nonEmpty) {
      throw new ParsingException("Too many arguments: " + newCtx.remainingArgs.mkString(", "))
    }
  }

  private def parseOptions(ctx: ParseContext): ParseContext = {
    var newCtx = ctx
    val argsToParse = newCtx.remainingArgs.takeWhile(_.startsWith("-"))

    for (arg <- argsToParse) {
      findOptionForArg(newCtx.opts, arg) match {
        case None => throw ParsingException("No option found for " + arg)
        case Some((option, value)) =>
          // FIXME we're removing blindly...
          readAndSetVar(option, value)
          newCtx = newCtx.validate(option, arg)
      }
    }

    for (option <- newCtx.opts) {
      setVar(option, option.default)
    }

    newCtx
  }

  /**
    * @return the matching option along with its value
    */
  private def findOptionForArg(options: Set[_ <: CliOption[_]], arg: String): Option[(CliOption[_], String)] = {
    for (option <- options) {
      option.abbrev.foreach { abbrev =>
        if (arg == ("-" + abbrev)) {
          return Some(option, "")
        }
      }
      option.longName.foreach { longName =>
        if (arg == ("--" + longName)) {
          return Some(option, "")
        }
        val key = "--" + longName + "="
        if (arg.startsWith(key)) {
          return Some(option, arg.substring(key.length))
        }
      }
    }
    None
  }

  private[this] def readAndSetVar(arg: CliAttribute[_] with SingleArgAttribute[_], strValue: String): Unit = {
    try {
      val value = arg.reader.reads(strValue)
      setVar(arg, value)
    } catch {
      case ReadException(value, expected) =>
        throw new ParsingException(s"Incorrect parameter ${arg.name} '$value', expected $expected")
    }
  }

  private[this] def setVar(arg: CliAttribute[_], value: Any): Unit = {
    setVar(arg.commandAttributeName, arg.tpe, value)
  }

  private[this] def setVar(name: String, tpe: Class[_], value: Any): Unit = {
    value match {
      case v: Seq[_] =>
        val seq = Option(getVar(name)).fold(Seq.empty[Any])(_.asInstanceOf[Seq[Any]])
        getClass.getMethod(name + "_$eq", tpe).invoke(this, seq ++ v)

      case _ =>
        getClass.getMethod(name + "_$eq", tpe).invoke(this, value.asInstanceOf[Object])
    }
  }

  private[this] def getVar(name: String): Any = {
    getClass.getMethod(name).invoke(this).asInstanceOf[Any]
  }

  def label = _name
  def arguments = _arguments
  def options = _options

  private[this] val _name = if (name != null) name
  else {
    getClass.spinalCaseName
  }

  private[this] var _arguments: List[CliArgument[_]] = Nil

  /**
    * Add to the end of the argument list.
    */
  private[clist] def enqueueArgument(arg: CliArgument[_]): Unit = {
    _arguments :+= arg
  }

  private[this] var _options: Set[CliOption[_]] = Set.empty
  private[clist] def addOption(opt: CliOption[_]): Unit = {
    _options += opt
  }

  private[this] var _validators: Set[Unit => Unit] = Set.empty

  def validate(validator: => Unit): Unit = {
    _validators += { _ => validator }
  }

  def validators: Set[Unit => Unit] = _validators

  private def applyValidators(): Unit = {
    _validators.foreach(_.apply(()))
  }

  def parsingError(msg: String): Nothing = {
    throw new ParsingException(msg)
  }
}

object Command {
  class ParseContext(val args: List[CliArgument[_]], val opts: Set[CliOption[_]], val remainingArgs: List[String]) {
    def firstArg: Option[String] = remainingArgs.headOption

    def validate(cmdArg: CliArgument[_] with SingleArgAttribute[_], arg: String): ParseContext = {
      new ParseContext(args.filter(_ != cmdArg), opts, remainingArgs.filter(_ != arg))
    }

    def validateAllArgs: ParseContext = new ParseContext(args, opts, Nil)

    def validate(opt: CliOption[_], arg: String): ParseContext = {
      new ParseContext(args, opts, remainingArgs.filter(_ != arg))
    }

    /**
      * @return (original argument, extract value)
      */
    def findArgForName(name: String): Option[(String,String)] = {
      remainingArgs.find(_.startsWith("--" + name + "=")).map { arg =>
        (arg, arg.substring(arg.indexOf("=") + 1))
      }
    }
  }

  object ParseContext {
    def apply(command: Command, args: List[String]): ParseContext = {
       new ParseContext(command.arguments, command.options, args)
    }
  }
}