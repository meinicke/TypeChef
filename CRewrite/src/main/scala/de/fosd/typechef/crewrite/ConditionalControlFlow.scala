package de.fosd.typechef.crewrite


import de.fosd.typechef.conditional._
import de.fosd.typechef.parser.c._
import java.util.IdentityHashMap
import de.fosd.typechef.featureexpr.{FeatureExprFactory, FeatureExpr}

// implements conditional control flow (cfg) on top of the typechef
// infrastructure
// at first sight the implementation of succ with a lot of private
// function seems overly complicated; however the structure allows
// also to implement pred
// consider the following points:

// the function definition an ast belongs to serves as the entry
// and exit node of the cfg, because we do not have special ast
// nodes for that, or we store everything in a ccfg itself with
// special nodes for entry and exit such as
// [1] http://soot.googlecode.com/svn/DUA_Forensis/src/dua/method/CFG.java

// normally pred succ are the same except for the following cases:
// 1. code in switch body that does not belong to a case block, but that has a label and
//    can be reached otherwise, e.g., switch (x) { l1: <code> case 0: .... }
// 2. infinite for loops without break or return statements in them, e.g.,
//    for (;;) { <code without break or return> }
//    this way we do not have any handle to jump into the for block from
//    its successors; we work around this issue by introducing a break statement
//    that always evaluates to true; for (;;) => for (;1;)

// for more information:
// iso/iec 9899 standard; committee draft
// [2] http://www.open-std.org/jtc1/sc22/wg14/www/docs/n1124.pdf

// TODO handling empty { } e.g., void foo() { { } }
// TODO support for (expr) ? (expr) : (expr);
// TODO analysis static gotos should have a label (if more labels must be unique according to feature expresssions)
// TODO analysis dynamic gotos should have a label
// TODO analysis: The expression of each case label shall be an integer constant expression and no two of
//                the case constant expressions in the same switch statement shall have the same value
//                after conversion.
// TODO analysis: There may be at most one default label in a switch statement.
// TODO analysis: we can continue this list only by looking at constrains in [2]


class CCFGCache {
  private val cache: IdentityHashMap[Product, List[AST]] = new IdentityHashMap[Product, List[AST]]()

  def update(k: Product, v: List[AST]) {
    cache.put(k, v)
  }

  def lookup(k: Product): Option[List[AST]] = {
    val v = cache.get(k)
    if (v != null) Some(v)
    else None
  }
}

trait ConditionalControlFlow extends ASTNavigation {

  private implicit def optList2ASTList(l: List[Opt[AST]]) = l.map(_.entry)

  private implicit def opt2AST(s: Opt[AST]) = s.entry

  private val predCCFGCache = new CCFGCache()
  private val succCCFGCache = new CCFGCache()

  // equal annotated AST elements
  type IfdefBlock  = List[AST]

  // #ifdef blocks that relate to each other, e.g.,
  // alternative: #if-(#elif)*-#else
  // optional:    #if-(#elif)*
  type OptAltBlock = List[IfdefBlock]

  // OptAltBlock with alt/opt annotation
  type TypedOptAltBlock = (Int, OptAltBlock)

  // determines predecessor of a given element
  // results are cached for secondary evaluation
  def pred(source: Product, env: ASTEnv): List[AST] = {
    predCCFGCache.lookup(source) match {
      case Some(v) => v
      case None => {
        var oldres: List[AST] = List()
        val ctx = env.featureExpr(source)
        var newres: List[AST] = predHelper(source, ctx, env)
        var changed = true

        while (changed) {
          changed = false
          oldres = newres
          newres = List()

          for (oldelem <- oldres) {
            var add2newres = List[AST]()
            oldelem match {

              case _: ReturnStatement if (!source.isInstanceOf[FunctionDef]) => add2newres = List()

              // a break statement shall appear only in or as a switch body or loop body
              // a break statement terminates execution of the smallest enclosing switch or
              // iteration statement (see standard [2])
              // so as soon as we hit a break statement and the break statement belongs to the same loop as we do
              // the break statement is not a valid predecessor
              case b: BreakStatement => {
                val b2b = findPriorASTElem2BreakStatement(b, env)

                assert(b2b.isDefined, "missing loop to break statement!")
                if (isPartOf(source, b2b.get)) add2newres = List()
                else add2newres = List(b)
              }
              // a continue statement shall appear only in a loop body
              // a continue statement causes a jump to the loop-continuation portion
              // of the smallest enclosing iteration statement
              case c: ContinueStatement => {
                val a2c = findPriorASTElem2ContinueStatement(source, env)
                val b2c = findPriorASTElem2ContinueStatement(c, env)

                if (a2c.isDefined && b2c.isDefined && a2c.get.eq(b2c.get)) {
                  a2c.get match {
                    case WhileStatement(expr, _) if (isPartOf(source, expr)) => add2newres = List(c)
                    case DoStatement(expr, _) if (isPartOf(source, expr)) => add2newres = List(c)
                    case ForStatement(_, Some(expr2), None, _) if (isPartOf(source, expr2)) => add2newres = List(c)
                    case ForStatement(_, _, Some(expr3), _) if (isPartOf(source, expr3)) => add2newres = List(c)
                    case _ => add2newres = List()
                  }
                } else add2newres = List()
              }
              // in case we hit an elif statement, we have to check whether a and the elif belong to the same if
              // if a belongs to an if
              // TODO should be moved to pred determination directly
              case e@ElifStatement(condition, _) => {
                val a2e = findPriorASTElem[IfStatement](source, env)
                val b2e = findPriorASTElem[IfStatement](e, env)

                if (a2e.isEmpty) { changed = true; add2newres = rollUp(e, oldelem, env.featureExpr(oldelem), env)}
                else if (a2e.isDefined && b2e.isDefined && a2e.get.eq(b2e.get)) {
                  changed = true
                  add2newres = getCondExprPred(condition, env.featureExpr(oldelem), env)
                }
                else {
                  changed = true
                  add2newres = rollUp(e, oldelem, env.featureExpr(oldelem), env)
                }
              }

              // goto statements
              // in general only label statements can be the source of goto statements
              // and only the ones that have the same name
              case s@GotoStatement(Id(name)) => {
                if (source.isInstanceOf[LabelStatement]) {
                  val lname = source.asInstanceOf[LabelStatement].id.name
                  if (name == lname) add2newres = List(s)
                }
              }

              // for all other elements we use rollup and check whether the outcome of rollup differs from
              // its input (oldelem)
              case _: AST => {
                add2newres = rollUp(source, oldelem, env.featureExpr(oldelem), env)
                if (add2newres.size > 1 || (add2newres.size > 0 && add2newres.head.ne(oldelem))) changed = true
              }
            }

            // add only elements that are not in newres so far
            // add them add the end to keep the order of the elements
            for (addnew <- add2newres)
              if (newres.map(_.eq(addnew)).foldLeft(false)(_ || _).unary_!) newres = newres ++ List(addnew)
          }
        }
        predCCFGCache.update(source, newres)
        newres
      }
    }
  }

  // checks reference equality of e in a given struture t (either product or list)
  def isPartOf(subterm: Product, term: Any): Boolean = {
    term match {
      case _: Product if (subterm.asInstanceOf[AnyRef].eq(term.asInstanceOf[AnyRef])) => true
      case l: List[_] => l.map(isPartOf(subterm, _)).exists(_ == true)
      case p: Product => p.productIterator.toList.map(isPartOf(subterm, _)).exists(_ == true)
      case _ => false
    }
  }

  def predHelper(source: Product, ctx: FeatureExpr, env: ASTEnv): List[AST] = {

    // helper method to handle a switch, if we come from a case or a default statement
    def handleSwitch(t: AST) = {
      val prior_switch = findPriorASTElem[SwitchStatement](t, env)
      assert(prior_switch.isDefined, "default or case statements should always occur withing a switch definition")
      prior_switch.get match {
        case SwitchStatement(expr, _) => getExprPred(expr, ctx, env) ++ getStmtPred(t, ctx, env)
      }
    }

    source match {
      case t: CaseStatement => handleSwitch(t)
      case t: DefaultStatement => handleSwitch(t)

      case t@LabelStatement(Id(n), _) => {
        findPriorASTElem[FunctionDef](t, env) match {
          case None => assert(false, "label statements should always occur within a function definition"); List()
          case Some(f) => {
            val l_gotos = filterASTElems[GotoStatement](f, env.featureExpr(t), env)
            // filter gotostatements with the same id as the labelstatement
            // and all gotostatements with dynamic target
            val l_gotos_filtered = l_gotos.filter({
              case GotoStatement(Id(name)) => if (n == name) true else false
              case _ => true
            })
            val l_preds = getStmtPred(t, ctx, env).
              flatMap({ x => rollUp(source, x, env.featureExpr(x), env) })
            l_gotos_filtered ++ l_preds
          }
        }
      }

      case o: Opt[_] => predHelper(childAST(o), ctx, env)
      case c: Conditional[_] => predHelper(childAST(c), ctx, env)

      case f@FunctionDef(_, _, _, CompoundStatement(List())) => List(f)
      case f@FunctionDef(_, _, _, stmt) => predHelper(childAST(stmt), ctx, env) ++
         filterAllASTElems[ReturnStatement](f, env.featureExpr(f))
      case c@CompoundStatement(innerStatements) => getCompoundPred(innerStatements, c, ctx, env)

      case s: Statement => getStmtPred(s, ctx, env)
      case _ => followPred(source, ctx, env)
    }
  }

  def succ(source: Product, env: ASTEnv): List[AST] = {
    succCCFGCache.lookup(source) match {
      case Some(v) => v
      case None => {
        var oldres: List[AST] = List()
        val ctx = env.featureExpr(source)
        var newres: List[AST] = succHelper(source, ctx, env)
        var changed = true

        while (changed) {
          changed = false
          oldres = newres
          newres = List()
          for (oldelem <- oldres) {
            var add2newres: List[AST] = List()
            oldelem match {
              case _: IfStatement => changed = true; add2newres = succHelper(oldelem, env.featureExpr(oldelem), env)
              case _: ElifStatement => changed = true; add2newres = succHelper(oldelem, env.featureExpr(oldelem), env)
              case _: SwitchStatement => changed = true; add2newres = succHelper(oldelem, env.featureExpr(oldelem), env)
              case _: CompoundStatement => changed = true; add2newres = succHelper(oldelem, env.featureExpr(oldelem), env)
              case _: DoStatement => changed = true; add2newres = succHelper(oldelem, env.featureExpr(oldelem), env)
              case _: WhileStatement => changed = true; add2newres = succHelper(oldelem, env.featureExpr(oldelem), env)
              case _: ForStatement => changed = true; add2newres = succHelper(oldelem, env.featureExpr(oldelem), env)
              case _: DefaultStatement => changed = true; add2newres = succHelper(oldelem, env.featureExpr(oldelem), env)
              case _ => add2newres = List(oldelem)
            }

            // add only elements that are not in newres so far
            // add them add the end to keep the order of the elements
            for (addnew <- add2newres)
              if (newres.map(_.eq(addnew)).foldLeft(false)(_ || _).unary_!) newres = newres ++ List(addnew)
          }
        }
        succCCFGCache.update(source, newres)
        newres
      }
    }
  }

  private def succHelper(source: Product, ctx: FeatureExpr, env: ASTEnv): List[AST] = {
    source match {
      // ENTRY element
      case f@FunctionDef(_, _, _, CompoundStatement(List())) => List(f) // TODO after rewrite of compound handling -> could be removed
      case f@FunctionDef(_, _, _, stmt) => succHelper(stmt, ctx, env)

      // EXIT element
      case t@ReturnStatement(_) => {
        findPriorASTElem[FunctionDef](t, env) match {
          case None => assert(false, "return statement should always occur within a function statement"); List()
          case Some(f) => List(f)
        }
      }

      case t@CompoundStatement(l) => getCompoundSucc(l, t, ctx, env)

      case o: Opt[_] => succHelper(o.entry.asInstanceOf[Product], ctx, env)
      case t: Conditional[_] => succHelper(childAST(t), ctx, env)

      // loop statements
      case ForStatement(None, Some(expr2), None, One(EmptyStatement())) => getExprSucc(expr2, ctx, env)
      case ForStatement(None, Some(expr2), None, One(CompoundStatement(List()))) => getExprSucc(expr2, ctx, env)
      case t@ForStatement(expr1, expr2, expr3, s) => {
        if (expr1.isDefined) getExprSucc(expr1.get, ctx, env)
        else if (expr2.isDefined) getExprSucc(expr2.get, ctx, env)
        else getCondStmtSucc(t, s, ctx, env)
      }
      case WhileStatement(expr, One(EmptyStatement())) => getExprSucc(expr, ctx, env)
      case WhileStatement(expr, One(CompoundStatement(List()))) => getExprSucc(expr, ctx, env)
      case WhileStatement(expr, _) => getExprSucc(expr, ctx, env)
      case DoStatement(expr, One(CompoundStatement(List()))) => getExprSucc(expr, ctx, env)
      case t@DoStatement(_, s) => getCondStmtSucc(t, s, ctx, env)

      // conditional statements
      case t@IfStatement(condition, _, _, _) => getCondExprSucc(condition, ctx, env)
      case t@ElifStatement(condition, _) => getCondExprSucc(condition, ctx, env)
      case SwitchStatement(expr, _) => getExprSucc(expr, ctx, env)

      case t@BreakStatement() => {
        val e2b = findPriorASTElem2BreakStatement(t, env)
        assert(e2b.isDefined, "break statement should always occur within a for, do-while, while, or switch statement")
        getStmtSucc(e2b.get, ctx, env)
      }
      case t@ContinueStatement() => {
        val e2c = findPriorASTElem2ContinueStatement(t, env)
        assert(e2c.isDefined, "continue statement should always occur within a for, do-while, or while statement")
        e2c.get match {
          case t@ForStatement(_, expr2, expr3, s) => {
            if (expr3.isDefined) getExprSucc(expr3.get, ctx, env)
            else if (expr2.isDefined) getExprSucc(expr2.get, ctx, env)
            else getCondStmtSucc(t, s, ctx, env)
          }
          case WhileStatement(expr, _) => getExprSucc(expr, ctx, env)
          case DoStatement(expr, _) => getExprSucc(expr, ctx, env)
          case _ => List()
        }
      }
      case t@GotoStatement(Id(l)) => {
        findPriorASTElem[FunctionDef](t, env) match {
          case None => assert(false, "goto statement should always occur within a function definition"); List()
          case Some(f) => {
            val l_list = filterAllASTElems[LabelStatement](f, env.featureExpr(t), env).filter(_.id.name == l)
            if (l_list.isEmpty) getStmtSucc(t, ctx, env)
            else l_list
          }
        }
      }
      // in case we have an indirect goto dispatch all goto statements
      // within the function (this is our invariant) are possible targets of this goto
      // so fetch the function statement and filter for all label statements
      case t@GotoStatement(PointerDerefExpr(_)) => {
        findPriorASTElem[FunctionDef](t, env) match {
          case None => assert(false, "goto statement should always occur within a function definition"); List()
          case Some(f) => {
            val l_list = filterAllASTElems[LabelStatement](f, env.featureExpr(t))
            if (l_list.isEmpty) getStmtSucc(t, ctx, env)
            else l_list
          }
        }
      }

      case t@CaseStatement(_, Some(s)) => getCondStmtSucc(t, s, ctx, env)
      case t: CaseStatement => getStmtSucc(t, ctx, env)

      case t@DefaultStatement(Some(s)) => getCondStmtSucc(t, s, ctx, env)
      case t: DefaultStatement => getStmtSucc(t, ctx, env)

      case t: Statement => getStmtSucc(t, ctx, env)
      case t => followSucc(t, ctx, env)
    }
  }

  private def getCondStmtSucc(p: AST, c: Conditional[_], ctx: FeatureExpr, env: ASTEnv): List[AST] = {
    c match {
      case Choice(_, thenBranch, elseBranch) =>
        getCondStmtSucc(p, thenBranch, ctx, env) ++ getCondStmtSucc(p, elseBranch, ctx, env)
      case One(CompoundStatement(l)) => getCompoundSucc(l, c, ctx, env)
      case One(s: Statement) => List(s)
    }
  }

  private def getCondStmtPred(p: AST, c: Conditional[_], ctx: FeatureExpr, env: ASTEnv): List[AST] = {
    c match {
      case Choice(_, thenBranch, elseBranch) =>
        getCondStmtPred(p, thenBranch, ctx, env) ++ getCondStmtPred(p, elseBranch, ctx, env)
      case o@One(CompoundStatement(l)) => getCompoundPred(l, o, ctx, env)
      case One(s: Statement) => List(s)
    }
  }

  private def getExprSucc(e: Expr, ctx: FeatureExpr, env: ASTEnv) = {
    e match {
      case c@CompoundStatementExpr(CompoundStatement(innerStatements)) =>
        getCompoundSucc(innerStatements, c, ctx, env)
      case _ => List(e)
    }
  }

  private def getCondExprSucc(cexp: Conditional[Expr], ctx: FeatureExpr, env: ASTEnv): List[AST] = {
    cexp match {
      case One(value) => getExprSucc(value, ctx, env)
      case Choice(_, thenBranch, elseBranch) =>
        getCondExprSucc(thenBranch, env.featureExpr(thenBranch), env) ++
          getCondExprSucc(elseBranch, env.featureExpr(elseBranch), env)
    }
  }

  private def getExprPred(exp: Expr, ctx: FeatureExpr, env: ASTEnv) = {
    exp match {
      case t@CompoundStatementExpr(CompoundStatement(innerStatements)) => getCompoundPred(innerStatements, t, ctx, env)
      case _ => List(exp)
    }
  }

  private def getCondExprPred(cexp: Conditional[Expr], ctx: FeatureExpr, env: ASTEnv): List[AST] = {
    cexp match {
      case One(value) => getExprPred(value, ctx, env)
      case Choice(_, thenBranch, elseBranch) =>
        getCondExprPred(thenBranch, env.featureExpr(thenBranch), env) ++
          getCondExprPred(elseBranch, env.featureExpr(elseBranch), env)
    }
  }

  // handling of successor determination of nested structures, such as for, while, ... and next element in a list
  // of statements
  private def followSucc(nested_ast_elem: Product, ctx: FeatureExpr, env: ASTEnv): List[AST] = {
    nested_ast_elem match {
      case t: ReturnStatement => {
        findPriorASTElem[FunctionDef](t, env) match {
          case None => assert(false, "return statement should always occur within a function statement"); List()
          case Some(f) => List(f)
        }
      }
      case _ => {
        val surrounding_parent = parentAST(nested_ast_elem, env)
        surrounding_parent match {
          // loops
          case t@ForStatement(Some(expr1), expr2, _, s) if (isPartOf(nested_ast_elem, expr1)) =>
            if (expr2.isDefined) getExprSucc(expr2.get, ctx, env)
            else getCondStmtSucc(t, s, ctx, env)
          case t@ForStatement(_, Some(expr2), _, s) if (isPartOf(nested_ast_elem, expr2)) =>
            getStmtSucc(t, ctx, env) ++ getCondStmtSucc(t, s, ctx, env)
          case t@ForStatement(_, expr2, Some(expr3), s) if (isPartOf(nested_ast_elem, expr3)) =>
            if (expr2.isDefined) getExprSucc(expr2.get, ctx, env)
            else getCondStmtSucc(t, s, ctx, env)
          case t@ForStatement(_, expr2, expr3, s) if (isPartOf(nested_ast_elem, s)) => {
            if (expr3.isDefined) getExprSucc(expr3.get, ctx, env)
            else if (expr2.isDefined) getExprSucc(expr2.get, ctx, env)
            else getCondStmtSucc(t, s, ctx, env)
          }
          case t@WhileStatement(expr, s) if (isPartOf(nested_ast_elem, expr)) =>
            getCondStmtSucc(t, s, ctx, env) ++ getStmtSucc(t, ctx, env)
          case WhileStatement(expr, s) => List(expr)
          case t@DoStatement(expr, s) if (isPartOf(nested_ast_elem, expr)) =>
            getCondStmtSucc(t, s, ctx, env) ++ getStmtSucc(t, ctx, env)
          case DoStatement(expr, s) => List(expr)

          // conditional statements
          // we are in the condition of the if statement
          case t@IfStatement(condition, thenBranch, elifs, elseBranch) if (isPartOf(nested_ast_elem, condition)) => {
            var res = getCondStmtSucc(t, thenBranch, ctx, env)
            if (!elifs.isEmpty) res = res ++ getCompoundSucc(elifs, t, ctx, env)
            if (elifs.isEmpty && elseBranch.isDefined) res = res ++ getCondStmtSucc(t, elseBranch.get, ctx, env)
            if (elifs.isEmpty && !elseBranch.isDefined) res = res ++ getStmtSucc(t, ctx, env)
            res
          }

          // either go to next ElifStatement, ElseBranch, or next statement of the surrounding IfStatement
          // filtering is necessary, as else branches are not considered by getSuccSameLevel
          case t@ElifStatement(condition, thenBranch) if (isPartOf(nested_ast_elem, condition)) => {
            var res: List[AST] = List()
            getElifSucc(t, ctx, env) match {
              case Left(l)  => res ++= l
              case Right(l) => {
                res ++= l
                parentAST(t, env) match {
                  case tp@IfStatement(_, _, _, None) => res ++= getStmtSucc(tp, ctx, env)
                  case IfStatement(_, _, _, Some(elseBranch)) => res ++= getCondStmtSucc(t, elseBranch, ctx, env)
                }
              }
            }

            res ++ getCondStmtSucc(t, thenBranch, ctx, env)
          }
          case t: ElifStatement => followSucc(t, ctx, env)

          // the switch statement behaves like a dynamic goto statement;
          // based on the expression we jump to one of the case statements or default statements
          // after the jump the case/default statements do not matter anymore
          // when hitting a break statement, we jump to the end of the switch
          case t@SwitchStatement(expr, s) if (isPartOf(nested_ast_elem, expr)) => {
            var res: List[AST] = List()
            if (isPartOf(nested_ast_elem, expr)) {
              res = filterCaseStatements(s, env.featureExpr(t), env)
              val dcase = filterDefaultStatements(s, env.featureExpr(t), env)

              if (dcase.isEmpty) res = res ++ getStmtSucc(t, ctx, env)
              else res = res ++ dcase
            }
            res
          }

          case t: Expr => followSucc(t, ctx, env)
          case t: Statement => getStmtSucc(t, ctx, env)

          case t: FunctionDef => List(t)
          case _ => List()
        }
      }
    }
  }

  // method to catch surrounding ast element, which precedes the given nested_ast_element
  private def followPred(nested_ast_elem: Product, ctx: FeatureExpr, env: ASTEnv): List[AST] = {

    def handleSwitch(t: AST) = {
      val prior_switch = findPriorASTElem[SwitchStatement](t, env)
      assert(prior_switch.isDefined, "default statement without surrounding switch")
      prior_switch.get match {
        case SwitchStatement(expr, _) => {
          val lconds = getExprPred(expr, ctx, env)
          if (env.previous(t) != null) lconds ++ getStmtPred(t, ctx, env)
          else {
            val tparent = parentAST(t, env)
            if (tparent.isInstanceOf[CaseStatement]) tparent :: lconds  // TODO rewrite, nested cases.
            else lconds ++ getStmtPred(tparent, ctx, env)
          }
        }
      }
    }

    nested_ast_elem match {

      // case or default statements belong only to switch statements
      case t: CaseStatement => handleSwitch(t)
      case t: DefaultStatement => handleSwitch(t)

      case _ => {
        val surrounding_parent = parentAST(nested_ast_elem, env)
        surrounding_parent match {

          // loop statements

          // for statements consists of of (init, break, inc, body)
          // we are in one of these elements
          // init
          case t@ForStatement(Some(expr1), _, _, _) if (isPartOf(nested_ast_elem, expr1)) =>
            getStmtPred(t, ctx, env)
          // inc
          case t@ForStatement(_, _, Some(expr3), s) if (isPartOf(nested_ast_elem, expr3)) =>
            getCondStmtPred(t, s, ctx, env) ++ filterContinueStatements(s, env.featureExpr(t), env)
          // break
          case t@ForStatement(None, Some(expr2), None, One(CompoundStatement(List()))) =>
            List(expr2) ++ getStmtPred(t, ctx, env)
          case t@ForStatement(expr1, Some(expr2), expr3, s) if (isPartOf(nested_ast_elem, expr2)) => {
            var res: List[AST] = List()
            if (expr1.isDefined) res ++= getExprPred(expr1.get, ctx, env)
            else res ++= getStmtPred(t, ctx, env)
            if (expr3.isDefined) res ++= getExprPred(expr3.get, ctx, env)
            else {
              res ++= getCondStmtPred(t, s, ctx, env)
              res ++= filterContinueStatements(s, env.featureExpr(t), env)
            }
            res
          }
          // s
          case t@ForStatement(expr1, expr2, expr3, s) if (isPartOf(nested_ast_elem, s)) =>
            if (expr2.isDefined) getExprPred(expr2.get, ctx, env)
            else if (expr3.isDefined) getExprPred(expr3.get, ctx, env)
            else {
              var res: List[AST] = List()
              if (expr1.isDefined) res = res ++ getExprPred(expr1.get, ctx, env)
              else res = getStmtPred(t, ctx, env) ++ res
              res = res ++ getCondStmtPred(t, s, ctx, env)
              res
            }

          // while statement consists of (expr, s)
          // special case; we handle empty compound statements here directly because otherwise we do not terminate
          case t@WhileStatement(expr, One(CompoundStatement(List()))) if (isPartOf(nested_ast_elem, expr)) =>
            getStmtPred(t, ctx, env) ++ List(expr)
          case t@WhileStatement(expr, s) if (isPartOf(nested_ast_elem, expr)) =>
            (getStmtPred(t, ctx, env) ++ getCondStmtPred(t, s, ctx, env) ++
              filterContinueStatements(s, env.featureExpr(t), env))
          case t@WhileStatement(expr, _) => {
            if (nested_ast_elem.eq(expr)) getStmtPred(t, ctx, env)
            else getExprPred(expr, ctx, env)
          }

          // do statement consists of (expr, s)
          // special case: we handle empty compound statements here directly because otherwise we do not terminate
          case t@DoStatement(expr, One(CompoundStatement(List()))) if (isPartOf(nested_ast_elem, expr)) =>
            getStmtPred(t, ctx, env) ++ List(expr)
          case t@DoStatement(expr, s) if (isPartOf(nested_ast_elem, expr)) =>
            getCondStmtPred(t, s, ctx, env) ++ filterContinueStatements(s, env.featureExpr(t), env)
          case t@DoStatement(expr, s) => {
            if (isPartOf(nested_ast_elem, expr)) getCondStmtPred(t, s, ctx, env)
            else getExprPred(expr, ctx, env) ++ getStmtPred(t, ctx, env)
          }

          // conditional statements
          // if statement: control flow comes either out of:
          // elseBranch: elifs + condition is the result
          // elifs: rest of elifs + condition
          // thenBranch: condition
          case t@IfStatement(condition, thenBranch, elifs, elseBranch) => {
            if (isPartOf(nested_ast_elem, condition)) getStmtPred(t, ctx, env)
            else if (isPartOf(nested_ast_elem, thenBranch)) getCondExprPred(condition, ctx, env)
            else if (isPartOf(nested_ast_elem, elseBranch)) {
              if (elifs.isEmpty) getCondExprPred(condition, ctx, env)
              else {
                getCompoundPred(elifs, t, ctx, env).flatMap({
                  case ElifStatement(elif_condition, _) => getCondExprPred(elif_condition, ctx, env)
                  case x => List(x)
                })
              }
            } else {
              getStmtPred(nested_ast_elem.asInstanceOf[AST], ctx, env)
            }
          }

          // pred of thenBranch is the condition itself
          // and if we are in condition, we strike for a previous elifstatement or the if itself using
          // getPredSameLevel
          case t@ElifStatement(condition, thenBranch) => {
            if (isPartOf(nested_ast_elem, condition)) predElifStatement(t, ctx, env)
            else getCondExprPred(condition, ctx, env)
          }

          case SwitchStatement(expr, s) if (isPartOf(nested_ast_elem, s)) => getExprPred(expr, ctx, env)
          case t: CaseStatement => List(t)

          // pred of default is either the expression of the switch, which is
          // returned by handleSwitch, or a previous statement (e.g.,
          // switch (exp) {
          // ...
          // label1:
          // default: ...)
          // as part of a fall through (sequence of statements without a break and that we catch
          // with getStmtPred
          case t: DefaultStatement => handleSwitch(t) ++ getStmtPred(t, ctx, env)

          case t: CompoundStatementExpr => followPred(t, ctx, env)
          case t: Statement => getStmtPred(t, ctx, env)
          case t: FunctionDef => List(t)
          case _ => List()
        }
      }
    }
  }

  private def predElifStatement(a: ElifStatement, ctx: FeatureExpr, env: ASTEnv): List[AST] = {
    val surrounding_if = parentAST(a, env)
    surrounding_if match {
      case IfStatement(condition, thenBranch, elifs, elseBranch) => {
        var res: List[AST] = List()
        val prev_elifs = elifs.reverse.dropWhile(_.entry.eq(a.asInstanceOf[AnyRef]).unary_!).drop(1)
        val ifdef_blocks = determineIfdefBlocks(prev_elifs, env)
        res = res ++ determineFollowingElements(ctx, ifdef_blocks, env).merge

        // if no previous elif statement is found, the result is condition
        if (!res.isEmpty) {
          var newres: List[AST] = List()
          for (elem_res <- res) {
            elem_res match {
              case ElifStatement(elif_condition, _) =>
                newres = getCondExprPred(elif_condition, ctx, env) ++ newres
              case _ => newres = elem_res :: newres
            }
          }
          newres
        }
        else getCondExprPred(condition, ctx, env)
      }
      case _ => List()
    }
  }

  // method to find a prior loop statement that belongs to a given break statement
  private def findPriorASTElem2BreakStatement(a: Product, env: ASTEnv): Option[AST] = {
    val aparent = env.parent(a)
    aparent match {
      case t: ForStatement => Some(t)
      case t: WhileStatement => Some(t)
      case t: DoStatement => Some(t)
      case t: SwitchStatement => Some(t)
      case null => None
      case p: Product => findPriorASTElem2BreakStatement(p, env)
    }
  }

  // method to find prior element to a continue statement
  private def findPriorASTElem2ContinueStatement(a: Product, env: ASTEnv): Option[AST] = {
    val aparent = env.parent(a)
    aparent match {
      case t: ForStatement => Some(t)
      case t: WhileStatement => Some(t)
      case t: DoStatement => Some(t)
      case null => None
      case p: Product => findPriorASTElem2ContinueStatement(p, env)
    }
  }

  // we have to check possible successor nodes in at max three steps:
  // 1. get direct successors with same annotation; if yes stop; if not go to step 2.
  // 2. get all annotated elements at the same level and check whether we find a definite set of successor nodes
  //    if yes stop; if not go to step 3.
  // 3. get the parent of our node and determine successor nodes of it
  private def getStmtSucc(s: AST, ctx: FeatureExpr, env: ASTEnv): List[AST] = {

    // check whether next statement has the same annotation if yes return it, if not
    // check the following ifdef blocks; 1.
    val snext = nextAST(s, env)
    if (snext != null && (env.featureExpr(snext) equivalentTo ctx)) return List(snext)
    else {
      val lprevnext = getPrevAndNextListMembers(s, env)
      val ifdefblocks = determineIfdefBlocks(lprevnext, env)
      val taillist = getTailListSucc(s, ifdefblocks)
      determineFollowingElements(ctx, taillist.drop(1), env) match {
        case Left(slist) => slist // 2.
        case Right(slist) => slist ++ followSucc(s, ctx, env) // 3.
      }
    }
  }

  // get previous and next list member of s
  private def getPrevAndNextListMembers(s: AST, env: ASTEnv) = prevASTElems(s, env) ++ nextASTElems(s, env).drop(1)

  // specialized version of getStmtSucc for ElifStatements
  private def getElifSucc(s: ElifStatement, ctx: FeatureExpr, env: ASTEnv): Either[List[AST], List[AST]] = {
    
    val snext = nextAST(s, env)
    if (snext != null && (env.featureExpr(snext) equivalentTo ctx)) return Left(List(snext))
    else {
      val lprevnext = getPrevAndNextListMembers(s, env)
      val ifdefblocks = determineIfdefBlocks(lprevnext, env)
      val taillist = getTailListSucc(s, ifdefblocks)      
      determineFollowingElements(ctx, taillist.drop(1), env)
    }
  }

  // this method filters BreakStatements
  // a break belongs to next outer loop (for, while, do-while)
  // or a switch statement (see [2])
  // use this method with the loop or switch body!
  // so we recursively go over the structure of the ast elems
  // in case we find a break, we add it to the result list
  // in case we hit another loop or switch we return the empty list
  private def filterBreakStatements(c: Conditional[Statement], ctx: FeatureExpr, env: ASTEnv): List[BreakStatement] = {
    def filterBreakStatementsHelper(a: Any): List[BreakStatement] = {
      a match {
        case t: BreakStatement => if (env.featureExpr(t) implies ctx isSatisfiable()) List(t) else List()
        case _: SwitchStatement => List()
        case _: ForStatement => List()
        case _: WhileStatement => List()
        case _: DoStatement => List()
        case l: List[_] => l.flatMap(filterBreakStatementsHelper(_))
        case x: Product => x.productIterator.toList.flatMap(filterBreakStatementsHelper(_))
        case _ => List()
      }
    }
    filterBreakStatementsHelper(c)
  }

  // this method filters ContinueStatements
  // according to [2]: A continue statement shall appear only in or as a
  // loop body
  // use this method only with the loop body!
  private def filterContinueStatements(c: Conditional[Statement], ctx: FeatureExpr, env: ASTEnv): List[ContinueStatement] = {
    def filterContinueStatementsHelper(a: Any): List[ContinueStatement] = {
      a match {
        case t: ContinueStatement => if (env.featureExpr(t) implies ctx isSatisfiable()) List(t) else List()
        case _: ForStatement => List()
        case _: WhileStatement => List()
        case _: DoStatement => List()
        case l: List[_] => l.flatMap(filterContinueStatementsHelper(_))
        case x: Product => x.productIterator.toList.flatMap(filterContinueStatementsHelper(_))
        case _ => List()
      }
    }
    filterContinueStatementsHelper(c)
  }

  // this method filters all CaseStatements
  private def filterCaseStatements(c: Conditional[Statement], ctx: FeatureExpr, env: ASTEnv): List[CaseStatement] = {
    def filterCaseStatementsHelper(a: Any): List[CaseStatement] = {
      a match {
        case t@CaseStatement(_, s) =>
          (if (env.featureExpr(t) implies ctx isSatisfiable()) List(t) else List()) ++
            (if (s.isDefined) filterCaseStatementsHelper(s.get) else List())
        case SwitchStatement => List()
        case l: List[_] => l.flatMap(filterCaseStatementsHelper(_))
        case x: Product => x.productIterator.toList.flatMap(filterCaseStatementsHelper(_))
        case _ => List()
      }
    }
    filterCaseStatementsHelper(c)
  }

  // although the standard says that a case statement only has one default statement
  // we may have differently annotated default statements
  private def filterDefaultStatements(c: Conditional[Statement], ctx: FeatureExpr, env: ASTEnv): List[DefaultStatement] = {
    def filterDefaultStatementsHelper(a: Any): List[DefaultStatement] = {
      a match {
        case SwitchStatement => List()
        case t: DefaultStatement => if (env.featureExpr(t) implies ctx isSatisfiable()) List(t) else List()
        case l: List[_] => l.flatMap(filterDefaultStatementsHelper(_))
        case x: Product => x.productIterator.toList.flatMap(filterDefaultStatementsHelper(_))
        case _ => List()
      }
    }
    filterDefaultStatementsHelper(c)
  }

  // in predecessor determination we have to dig in into elements at certain points
  // we dig into ast that have an Conditional part, such as for, while, ...
  // source is the element that we compute the predecessor for
  // target is the current determined predecessor that might be evaluated further
  // ctx stores the context of target element
  // env is the ast environment that stores references to parents, siblings, and children
  private def rollUp(source: Product, target: AST, ctx: FeatureExpr, env: ASTEnv): List[AST] = {
    target match {

      // in general all elements from the different branches (thenBranch, elifs, elseBranch)
      // can be predecessors
      case t@IfStatement(condition, thenBranch, elifs, elseBranch) => {
        var res = List[AST]()
      
        if (elseBranch.isDefined) res ++= getCondStmtPred(t, elseBranch.get, ctx, env)
        if (!elifs.isEmpty) {
          for (Opt(f, elif@ElifStatement(_, thenBranch)) <- elifs) {
            if (f.implies(ctx).isSatisfiable())
              res ++= getCondStmtPred(elif, thenBranch, env.featureExpr(elif), env)
          }

          // without an else branch, the condition of elifs are possible predecessors of a
          if (elseBranch.isEmpty) res ++= getCompoundPred(elifs, t, ctx, env)
        }
        res ++= getCondStmtPred(t, thenBranch, ctx, env)

        if (elifs.isEmpty && elseBranch.isEmpty)
          res ++= getCondExprPred(condition, ctx, env)
        res.flatMap({ x => rollUp(source, x, env.featureExpr(x), env) })
      }
      case ElifStatement(condition, thenBranch) => {
        var res = List[AST]()
        res ++= getCondExprPred(condition, ctx, env)

        // check wether source is part of a possibly exising elsebranch;
        // if so we do not roll up the thenbranch
        findPriorASTElem[IfStatement](source, env) match {
          case None =>
          case Some(IfStatement(_, _, _, None)) => res ++= getCondStmtPred(target, thenBranch, ctx, env)
          case Some(IfStatement(_, _, _, Some(x))) => if (! isPartOf(source, x))
            res ++= getCondStmtPred(target, thenBranch, ctx, env)
        }

        res.flatMap({ x => rollUp(source, x, env.featureExpr(x), env) })
      }
      case t@SwitchStatement(expr, s) => {
        val lbreaks = filterBreakStatements(s, env.featureExpr(t), env)
        lazy val ldefaults = filterDefaultStatements(s, env.featureExpr(t), env)

        // if no break and default statement is there, possible predecessors are the expr of the switch itself
        // and the code after the last case
        if (lbreaks.isEmpty && ldefaults.isEmpty) {
          var res = getExprPred(expr, ctx, env)
          val listcasestmts = filterCaseStatements(s, ctx, env)

          if (! listcasestmts.isEmpty) {
            val lastcase = listcasestmts.last
            res ++= rollUpJumpStatement(lastcase, true, env.featureExpr(lastcase), env)
          }

          res
        }
        else if (ldefaults.isEmpty) lbreaks ++ getExprPred(expr, ctx, env)
        else lbreaks ++ ldefaults.flatMap({ x => rollUpJumpStatement(x, true, env.featureExpr(x), env) })
      }

      case t@WhileStatement(expr, s) => List(expr) ++ filterBreakStatements(s, env.featureExpr(t), env)
      case t@DoStatement(expr, s) => List(expr) ++ filterBreakStatements(s, env.featureExpr(t), env)
      case t@ForStatement(_, Some(expr2), _, s) => List(expr2) ++ filterBreakStatements(s, env.featureExpr(t), env)
      case t@ForStatement(_, _, _, s) => filterBreakStatements(s, env.featureExpr(t), env)

      case c@CompoundStatement(innerStatements) => getCompoundPred(innerStatements, c, ctx, env).
        flatMap({ x => rollUp(source, x, env.featureExpr(x), env) })

      case t@GotoStatement(PointerDerefExpr(_)) => {
        if (source.isInstanceOf[LabelStatement]) List(target)
        else {
          findPriorASTElem[FunctionDef](t, env) match {
            case None => assert(false, "goto statement should always occur within a function definition"); List()
            case Some(f) => {
              val l_list = filterAllASTElems[LabelStatement](f, env.featureExpr(t))
              if (l_list.isEmpty) List(target)
              else List()
            }
          }
        }
      }

      case _ => List(target)
    }
  }

  // we have a separate rollUp function for CaseStatement, DefaultStatement, and BreakStatement
  // because using rollUp in pred determination (see above) will return wrong results
  private def rollUpJumpStatement(a: AST, fromSwitch: Boolean, ctx: FeatureExpr, env: ASTEnv): List[AST] = {
    a match {
      case t@CaseStatement(_, Some(s)) => getCondStmtPred(t, s, ctx, env).
        flatMap({ x => rollUpJumpStatement(x, false, env.featureExpr(x), env) })

      // the code that belongs to the jump target default is either reachable via nextAST from the
      // default statement: this first case statement here
      // or the code is nested in the DefaultStatement, so we match it with the next case statement
      case t@DefaultStatement(_) if (nextAST(t, env) != null && fromSwitch) => {
        val dparent = findPriorASTElem[CompoundStatement](t, env)
        assert(dparent.isDefined, "default statement always occurs in a compound statement of a switch")
        dparent.get match {
          case c@CompoundStatement(innerStatements) => getCompoundPred(innerStatements, c, ctx, env)
        }
      }
      case t@DefaultStatement(Some(s)) => getCondStmtPred(t, s, ctx, env).
        flatMap({ x => rollUpJumpStatement(x, false, env.featureExpr(x), env) })
      case _: BreakStatement => List()
      case _ => List(a)
    }
  }

  // we have to check possible predecessor nodes in at max three steps:
  // 1. get direct predecessor with same annotation; if yes stop; if not go to step 2.
  // 2. get all annotated elements at the same level and check whether we find a definite set of predecessor nodes
  //    if yes stop; if not go to step 3.
  // 3. get the parent of our node and determine predecessor nodes of it
  private def getStmtPred(s: AST, ctx: FeatureExpr, env: ASTEnv): List[AST] = {

    // 1.
    val sprev = prevAST(s, env)
    if (sprev != null && (env.featureExpr(sprev) equivalentTo ctx)) {
      sprev match {
        case BreakStatement() => List()
        case a => List(a).flatMap({ x => rollUpJumpStatement(x, false, env.featureExpr(x), env) })
      }
    } else {
      val lprevnext = getPrevAndNextListMembers(s, env)
      val ifdefblocks = determineIfdefBlocks(lprevnext, env)
      val taillist = getTailListPred(s, ifdefblocks)
      val taillistreversed = taillist.map(_.reverse).reverse

      determineFollowingElements(ctx, taillistreversed.drop(1), env) match {
        case Left(plist) => plist.
          flatMap({ x => rollUpJumpStatement(x, false, env.featureExpr(x), env)}) // 2.
        case Right(plist) => plist.
          flatMap({ x => rollUpJumpStatement(x, false, env.featureExpr(x), env)}) ++ followPred(s, ctx, env) // 3.

      }
    }
  }

  // given a list of AST elements, determine successor AST elements based on feature expressions
  private def getCompoundSucc(l: List[AST], parent: Product, ctx: FeatureExpr, env: ASTEnv): List[AST] = {
    val ifdefblocks = determineIfdefBlocks(l, env)

    determineFollowingElements(ctx, ifdefblocks, env) match {
      case Left(slist) => slist
      case Right(slist) => slist ++ (if (l.isEmpty) followSucc(parent, ctx, env)
                                     else followSucc(l.head, ctx, env))
    }
  }

  // given a list of AST elements, determine predecessor AST elements based on feature expressions
  private def getCompoundPred(l: List[AST], parent: Product, ctx: FeatureExpr, env: ASTEnv): List[AST] = {
    val ifdefblocks = determineIfdefBlocks(l, env)
    val ifdefblocksreverse = ifdefblocks.map(_.reverse).reverse

    determineFollowingElements(ctx, ifdefblocksreverse, env) match {
      case Left(plist) => plist
      case Right(plist) => plist ++ (if (l.isEmpty) followPred(parent, ctx, env)
                                     else followPred(l.reverse.head, ctx, env))
    }
  }

  // get list with rev and all following lists
  private def getTailListSucc(rev: AST, l: List[IfdefBlock]): List[IfdefBlock] = {
    // iterate each sublist of the incoming tuples TypedOptAltBlock combine equality check
    // and drop elements in which s occurs

    def contains(ifdefbl: IfdefBlock): Boolean = {
      ifdefbl.exists( x => x.eq(rev) )
    }

    l.dropWhile(x => contains(x).unary_!)
  }

  // same as getTailListSucc but with reversed input
  // result list ist reversed again so we have a list of TypedOptBlocks with the last
  // block containing rev
  private def getTailListPred(rev: AST, l: List[IfdefBlock]): List[IfdefBlock] = {
    getTailListSucc(rev, l.reverse).reverse
  }

  // code works both for succ and pred determination
  // based on the type of the IfdefBlocks (True(0), Optional (1), Alternative (2))
  // the function computes the following elements
  //   context - represents of the element we come frome
  //   l - list of grouped/typed ifdef blocks
  //   env - hold AST environment (parent, children, next, ...)
  private def determineFollowingElements(context: FeatureExpr,
                                         l: List[IfdefBlock],
                                         env: ASTEnv): Either[List[AST], List[AST]] = {
    // context of all added AST nodes that have been added to res
    var rescontext: List[FeatureExpr] = List()

    var res = List[AST]()

    for (ifdefblock <- l) {
      // get the first element of the ifdef block and check
      val head = ifdefblock.head
      val bfexp = env.featureExpr(head)
      
      // context implies annotation directly
      if (context equivalentTo bfexp) return Left(res ++ List(head))

      // annotation of the block contradicts with context; do nothing
      else if ((context and bfexp) isContradiction()) { }

      // nodes of annotations that have been added before: e.g., ctx is true; A B A true
      // the second A should not be added again because if A is selected the first A would have been selected
      // and not the second one
      else if (rescontext.exists(_ equivalentTo bfexp)) { }

      // otherwise add element and update resulting context
      else {res = res ++ List(head); rescontext ::= bfexp}

      if (rescontext.fold(FeatureExprFactory.False)(_ or _) isTautology()) return Left(res)
    }
    Right(res)
  }

  // determine recursively all succs check
  def getAllSucc(i: AST, env: ASTEnv) = {
    var r = List[(AST, List[AST])]()
    var s = List(i)
    var d = List[AST]()
    var c: AST = null

    while (!s.isEmpty) {
      c = s.head
      s = s.drop(1)

      if (d.filter(_.eq(c)).isEmpty) {
        r = (c, succ(c, env)) :: r
        s = s ++ r.head._2
        d = d ++ List(c)
      }
    }
    r
  }

  // determine recursively all pred
  def getAllPred(i: AST, env: ASTEnv) = {
    var r = List[(AST, List[AST])]()
    var s = List(i)
    var d = List[AST]()
    var c: AST = null

    while (!s.isEmpty) {
      c = s.head
      s = s.drop(1)

      if (d.filter(_.eq(c)).isEmpty) {
        r = (c, pred(c, env)) :: r
        s = s ++ r.head._2
        d = d ++ List(c)
      }
    }
    r
  }

  // given an ast element x and its successors lx: x should be in pred(lx)
  def compareSuccWithPred(lsuccs: List[(AST, List[AST])], lpreds: List[(AST, List[AST])], env: ASTEnv): List[CCFGError] = {
    var errors: List[CCFGError] = List()

    // check that number of nodes match
    val sdiff = lsuccs.map(_._1).diff(lpreds.map(_._1))
    val pdiff = lpreds.map(_._1).diff(lsuccs.map(_._1))

    for (sdelem <- sdiff)
      errors = new CCFGErrorMis("is not present in preds!", sdelem, env.featureExpr(sdelem)) :: errors


    for (pdelem <- pdiff)
      errors = new CCFGErrorMis("is not present in succs!", pdelem, env.featureExpr(pdelem)) :: errors

    // check that number of edges match
    var succ_edges: List[(AST, AST)] = List()
    for ((ast_elem, succs) <- lsuccs) {
      for (succ <- succs) {
        succ_edges = (ast_elem, succ) :: succ_edges
      }
    }

    var pred_edges: List[(AST, AST)] = List()
    for ((ast_elem, preds) <- lpreds) {
      for (pred <- preds) {
        pred_edges = (ast_elem, pred) :: pred_edges
      }
    }

    // check succ/pred connection and print out missing connections
    // given two ast elems:
    //   a
    //   b
    // we check (a1, b1) successor
    // against  (b2, a2) predecessor
    for ((a1, b1) <- succ_edges) {
      var isin = false
      for ((b2, a2) <- pred_edges) {
        if (a1.eq(a2) && b1.eq(b2))
          isin = true
      }
      if (!isin) {
        errors = new CCFGErrorDir("is missing in preds", b1, env.featureExpr(b1), a1, env.featureExpr(a1)) :: errors
      }
    }

    // check pred/succ connection and print out missing connections
    // given two ast elems:
    //  a
    //  b
    // we check (b1, a1) predecessor
    // against  (a2, b2) successor
    for ((b1, a1) <- pred_edges) {
      var isin = false
      for ((a2, b2) <- succ_edges) {
        if (a1.eq(a2) && b1.eq(b2))
          isin = true
      }
      if (!isin) {
        errors = new CCFGErrorDir("is missing in succs", a1, env.featureExpr(a1), b1, env.featureExpr(b1)) :: errors
      }
    }

    errors
  }

  // given a comparison function f: pack consecutive elements of list elements into sublists
  private def pack[T](f: (T, T) => Boolean)(l: List[T]): List[List[T]] = {
    if (l.isEmpty) List()
    else (l.head :: l.tail.takeWhile(f(l.head, _))) :: pack[T](f)(l.tail.dropWhile(f(l.head, _)))
  }

  // given a list of Opt elements; pack elements with the same annotation into sublists
  private def determineIfdefBlocks(l: List[AST], env: ASTEnv): List[IfdefBlock] = {
    pack[AST](env.featureExpr(_) equivalentTo env.featureExpr(_))(l)
  }

  // given a list of lists that contain elements with the same annotation
  // pack ifdef blocks together that belong to each other, e.g., by forming
  // optional groups (#if-(#elif)*) or alternative groups (#if-(#elif)*-#else)
  private def groupIfdefBlocks(l: List[IfdefBlock], env: ASTEnv): List[OptAltBlock] = {

    // due to nesting of #ifdefs at different levels of the AST (annotation of a function vs.
    // annotation of a statement inside a function) AST elements do have a set of annotations
    // equal annotated AST elements (IfdefBlock) relate to each other and form optional or
    // alternative groups
    // the relation is that the latter one implies the not of the former one
    // order b, a (see l.reverse below)
    def checkImplication(a: AST, b: AST) = {
      // annotation sets for a and b
      val afexpset = env.featureSet(a)
      val bfexpset = env.featureSet(b)

      // determine common part of both elements
      val abcommon = afexpset.intersect(bfexpset)

      // determine unique fexp set elements for a and b, and form one expression
      val afexpuniq = (afexpset -- abcommon).foldLeft(FeatureExprFactory.True)(_ and _)
      val bfexpuniq = (bfexpset -- abcommon).foldLeft(FeatureExprFactory.True)(_ and _)

      // latter implies not former
      afexpuniq implies (bfexpuniq.not()) isTautology()
    }

    // reverse list for check later implies former
    // e.g., [A, B, C, D, E]
    val lreversed = l.reverse
    val res = pack[List[AST]]({(x, y) => checkImplication(x.head, y.head)})(lreversed)

    // e.g., [[E, D, C], [B, A]] => [[A, B], [C, D, E]]
    res.map(_.reverse).reverse
  }

  // get type of IfdefBlocks:
  // 0 -> only true values
  // 1 -> #if-(#elif)* block
  // 2 -> #if-(#elif)*-#else block
  private def typeOptAltBlocksSucc(l: List[OptAltBlock], ctx: FeatureExpr, env: ASTEnv): List[TypedOptAltBlock] = {
    if (l.isEmpty) Nil
    else {
      val lhead = l.head

      // get all feature expression sets of all elements of the optaltblock
      val lfexpsets = lhead.map( e => env.featureSet(e.head) )

      // determine the common part of all sets
      val common = lfexpsets.fold(lfexpsets.head)(_ & _)

      // determine the uniq feature expression of all elements in fexpsets
      val lfexpsuniq = lfexpsets.map( x => (x -- common).fold(FeatureExprFactory.True)(_ and _))

      // feature expression for the entire opt alt block
      val fexpoab = lfexpsuniq.fold(FeatureExprFactory.False)(_ or _)

      if ((ctx implies fexpoab) isTautology()) (0, lhead) :: typeOptAltBlocksPred(l.tail, ctx, env)
      else (1, lhead) :: typeOptAltBlocksPred(l.tail, ctx, env)
    }
  }

  private def typeOptAltBlocksPred(l: List[OptAltBlock], ctx: FeatureExpr, env: ASTEnv): List[TypedOptAltBlock] = {
    if (l.isEmpty) Nil
    else {
      val lhead = l.head

      // get all feature expression sets of all elements of the optaltblock
      val lfexpsets = lhead.map( e => env.featureSet(e.head) )

      // determine the common part of all sets
      val common = lfexpsets.fold(lfexpsets.head)(_ & _)

      // determine the uniq feature expression of all elements in fexpsets
      val lfexpsuniq = lfexpsets.map( x => (x -- common).fold(FeatureExprFactory.True)(_ and _))

      // feature expression for the entire opt alt block
      val fexpoab = lfexpsuniq.fold(FeatureExprFactory.False)(_ or _)

      if ((ctx implies fexpoab) isTautology()) (0, lhead) :: typeOptAltBlocksPred(l.tail, ctx, env)
      else (1, lhead) :: typeOptAltBlocksPred(l.tail, ctx, env)
    }
  }
}

