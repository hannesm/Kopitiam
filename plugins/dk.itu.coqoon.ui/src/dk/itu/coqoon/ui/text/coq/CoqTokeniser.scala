/* CoqTokeniser.scala
 * Coq recognition and tokenisation code (with nested comment support)
 * Copyright © 2015 Alexander Faithfull
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License. */

package dk.itu.coqoon.ui.text.coq

import dk.itu.coqoon.ui.text.{Tokeniser, TransitionTracker, PushdownAutomaton}

object CoqRecogniser extends PushdownAutomaton[Char] {
  import PushdownAutomaton.Element

  object States {
    import PushdownAutomaton.State
    val base = "Coq:"

    val coq = State(s"${base}Coq")
    val coqComment = State(s"${base}Coq comment")
    val coqString = State(s"${base}Coq string")

    val nearlyCoqComment = State(s"${base}Nearly Coq comment")
    val nearlyNestedCoqComment = State(s"${base}Nearly nested Coq comment")
    val nearlyOutOfCoqComment = State(s"${base}Nearly out of Coq comment")

    val coqStringEscape = State(s"${base}Coq string escape")
  }

  import States._
  import Actions._

  DefaultTransition(coq, coq)

  BasicTransition(coq, '"', coqString)
  DefaultTransition(coqString, coqString)
  BasicTransition(coqStringEscape, '\\', coqStringEscape)
  BasicTransition(coqString, '\"', coq)
  DefaultTransition(coqStringEscape, coqString)

  BasicTransition(coq, '(', nearlyCoqComment)
  BasicTransition(nearlyCoqComment, '"', coqString)
  DefaultTransition(nearlyCoqComment, coq)
  BasicTransition(nearlyCoqComment, '(', nearlyCoqComment)
  BasicTransition(nearlyCoqComment, '*', coqComment)

  DefaultTransition(coqComment, coqComment)
  val commentElement = Element("Coq comment")

  BasicTransition(coqComment, '(', nearlyNestedCoqComment)
  DefaultTransition(nearlyNestedCoqComment, coqComment)
  BasicTransition(nearlyNestedCoqComment, '(', nearlyNestedCoqComment)
  Transition(nearlyNestedCoqComment,
      None, Some('*'), Some(commentElement), coqComment)

  BasicTransition(coqComment, '*', nearlyOutOfCoqComment)
  DefaultTransition(nearlyOutOfCoqComment, coqComment)
  BasicTransition(nearlyOutOfCoqComment, '*', nearlyOutOfCoqComment)
  Transition(nearlyOutOfCoqComment,
      Some(commentElement), Some(')'), None, coqComment)
  BasicTransition(nearlyOutOfCoqComment, ')', coq)
}

object CoqTokeniser extends Tokeniser(CoqRecogniser) {
  import PushdownAutomaton.{State, Transition}
  import CoqRecogniser.{States => Coq}

  def coqInspector(t : Transition[Char]) : Option[(State, Int)] = t match {
    case Transition(f, _, _, _, t @ Coq.coqString) if f != t =>
      Some((t, 1))
    case Transition(f, _, _, _, t @ Coq.coqComment) if f != t =>
      Some((t, 2))

    case Transition(f, _, _, _, t @ Coq.coq) if f != t =>
      Some((t, 0))

    case _ =>
      None
  }

  TransitionInspector(coqInspector)
}

private object TransitionTrackerTest {
  private final val DOCUMENT1 = """
trivial. "STR" (* Comment *) Qed.
"""
  private final val DOCUMENT2 = """
Theorem t : True.
Proof. (
  (* This should be trivial (but has parentheses) (* and a (* very *) nested comment *)*)
  try *) "and there's a comment terminator hanging around out here" trivial.
Qed.
"""
  private final val DOCUMENT = DOCUMENT2
  def main(args : Array[String]) =
    for (DOCUMENT <- Seq(DOCUMENT1, DOCUMENT2)) {
      val d = new TransitionTracker(CoqRecogniser, CoqRecogniser.States.coq)
      var i = 0
      while (i < DOCUMENT.size) {
        d.update(i, 0, DOCUMENT.substring(i, i + 1), DOCUMENT)
        i += 1
      }
      println("Rewriting document")
      i = 0
      while (i < (DOCUMENT.size - 1)) {
        print(s"$i (${DOCUMENT.charAt(i)}), ")
        val preTrace = d.getExecutions
        d.update(i, 1, "  ", DOCUMENT)
        val midTrace = d.getExecutions
        d.update(i, 0, "", DOCUMENT)
        assert(d.getExecutions == midTrace,
            "contentless change modified the transitions")
        d.update(i, 2, DOCUMENT.substring(i, i + 1), DOCUMENT)
        assert(d.getExecutions == preTrace,
            "destructive change was not reverted")
        i += 1
      }
      println("done.")

      var pos = 0
      d.getExecutions foreach {
        case (exec, len) =>
          try {
            print(s"<$exec>${DOCUMENT.substring(pos, pos + len)}")
          } finally pos += len
      }
      println
    }
}