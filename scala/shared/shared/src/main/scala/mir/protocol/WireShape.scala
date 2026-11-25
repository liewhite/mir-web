package mir.protocol

import scala.collection.mutable
import scala.quoted.*

/**
 * 编译期展开协议类型的结构（#1289）：类名、字段名和字段类型、枚举/密封类型的分支（按声明顺序），递归到底，
 * 再取摘要。服务端和页面各自编译同一份 shared 代码，摘要一样就说明双方收发的 JSON 长得一样。
 *
 * 只看结构，不看手写编解码器里的取值映射（如 EnumCodec.byCode 的编号）和不透明类型（opaque type）背后的类型：
 * 那些改动很少，改了要在 Wire.Revision 上手动加一。
 */
object WireShape:
  /** 类型 A 的结构文字（测试看展开得对不对） */
  inline def shape[A]: String = ${ shapeExpr[A] }

  /** 类型 A 的结构摘要（SHA-256 前 16 位十六进制） */
  inline def digest[A]: String = ${ digestExpr[A] }

  private def shapeExpr[A: Type](using Quotes): Expr[String] = Expr(describe[A])

  private def digestExpr[A: Type](using Quotes): Expr[String] =
    val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(describe[A].getBytes("UTF-8"))
    Expr(bytes.take(8).map(b => f"${b & 0xff}%02x").mkString)

  private def describe[A: Type](using q: Quotes): String =
    import q.reflect.*
    // 展开过的类型第二次出现只写名字（递归类型、重复引用）
    val expanded = mutable.Set.empty[String]

    def walk(t0: TypeRepr): String =
      val t   = t0.dealias
      val sym = t.typeSymbol
      t match
        case OrType(a, b)  => s"(${walk(a)}|${walk(b)})"
        case AndType(a, b) => s"(${walk(a)}&${walk(b)})"
        // 带类型参数的（Option、List、Map……）：容器名加参数，不展开容器自己的分支
        case AppliedType(tycon, args) => s"${tycon.typeSymbol.fullName}[${args.map(walk).mkString(",")}]"
        case _ if sym.flags.is(Flags.Opaque) => sym.fullName
        case _ if sym.isClassDef && sym.flags.is(Flags.Case) =>
          once(sym.fullName)(sym.caseFields.map(f => s"${f.name}:${walk(t.memberType(f))}").mkString("(", ",", ")"))
        case _ if sym.isClassDef && sym.flags.is(Flags.Sealed) =>
          once(sym.fullName)(sym.children.map(branch).mkString("{", ",", "}"))
        case _ => sym.fullName

    /** 枚举/密封类型的一个分支：带字段的展开，不带的（enum 的简单 case、case object）只写名字 */
    def branch(child: Symbol): String =
      if child.isClassDef && !child.flags.is(Flags.Module) then walk(child.typeRef)
      else child.name

    def once(name: String)(body: => String): String =
      if expanded.contains(name) then name
      else
        expanded += name
        name + body

    walk(TypeRepr.of[A])
