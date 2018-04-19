package org.backuity.clist

import org.backuity.clist.util.{ReadException, Formatting}
import Formatting.ClassUtil
import org.backuity.ansi.AnsiFormatter.FormattedHelper


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
                  throw ParsingException(ansi"No argument provided for %bold{${cmdArg.name}}")
                case optArg: CliOptionalArgument[_] =>
                  setVar(cmdArg, optArg.default)
              }

            case Some(arg) =>
              readAndSetVar(sCmdArg, arg)
              newCtx = newCtx.validate(sCmdArg, arg)
          }

        case mult: MultipleCliArgument[_] =>
          if (newCtx.remainingArgs.isEmpty) {
            throw new ParsingException(ansi"Insufficient arguments for %bold{${mult.name}}")
          } else {
            try {
              val value = mult.reader.reads(newCtx.remainingArgs)
              setVar(mult, value)
              newCtx = newCtx.validateAllArgs
            } catch {
              case ReadException(value, expected) =>
                throw new ParsingException(s"Incorrect value for argument %bold{${mult.name}}, got '$value', expected $expected")
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
          if (value.isEmpty && option.isBoolean) {
            // invert the default boolean value so that boolean options can be true by
            // default and enabling them turn them false
            setVar(option, !option.default.asInstanceOf[Boolean])
          } else {
            readAndSetVar(option, value)
          }
          newCtx = newCtx.validate(option, arg)
      }
    }

    for (option <- newCtx.opts) {
      if (option.useEnv.contains(true)) {
        val envVarName = option.name.replace("-", "_").toUpperCase
        val envVarOrDefault =
          sys.env.get(envVarName)
            .map(option.reader.reads)
            .getOrElse(option.default)
        setVar(option, envVarOrDefault)
      } else {
        setVar(option, option.default)
      }
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
        val attributeName = arg match {
          case _: CliArgument[_] => "argument"
          case _: CliOption[_] => "option"
        }
        throw new ParsingException(ansi"Incorrect value for $attributeName %bold{${arg.name}}, got '$value', expected $expected")
    }
  }

  private[this] def setVar(arg: CliAttribute[_], value: Any): Unit = {
    setVar(arg.commandAttributeName, arg.tpe, value)
  }

  private[this] def setVar(name: String, tpe: Class[_], value: Any): Unit = {
    try
      getClass.getMethod(name + "_$eq", tpe).invoke(this, value.asInstanceOf[Object])
    catch {
      case ex: IllegalArgumentException =>
        throw new IllegalArgumentException(ansi"Attribute %bold{$name} of type ${tpe.getSimpleName} is incompatible with value '$value'", ex)
    }
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
      new ParseContext(args.filter(_ != cmdArg), opts, removeAt(remainingArgs, remainingArgs.indexOf(arg)))
    }

    def validateAllArgs: ParseContext = new ParseContext(args, opts, Nil)

    def validate(opt: CliOption[_], arg: String): ParseContext = {
      new ParseContext(args, opts.filter(_ != opt), removeAt(remainingArgs, remainingArgs.indexOf(arg)))
    }

    /**
      * @return (original argument, extract value)
      */
    def findArgForName(name: String): Option[(String,String)] = {
      remainingArgs.find(_.startsWith("--" + name + "=")).map { arg =>
        (arg, arg.substring(arg.indexOf("=") + 1))
      }
    }

    private def removeAt[T](lst: List[T], index: Int) : List[T] = {
      lst.patch(index, Nil, 1)
    }
  }

  object ParseContext {
    def apply(command: Command, args: List[String]): ParseContext = {
       new ParseContext(command.arguments, command.options, args)
    }
  }
}