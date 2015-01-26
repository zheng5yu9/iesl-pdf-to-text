package edu.umass.cs.iesl.iesl_pdf_to_text

import java.io.File

import scala.collection.JavaConversions.iterableAsScalaIterable 

import scala.collection.immutable.IntMap
import scala.collection.immutable.Set
import scala.collection.immutable.Map
import scala.collection.immutable.ListMap

import org.jdom2.filter.ElementFilter
import org.jdom2.Element
import org.jdom2.Document
import org.jdom2.util.IteratorIterable
import org.jdom2.input.SAXBuilder

import scala.io.Source

import org.scalatest._

import scala.sys.process._

class PdfToSvgTest extends FlatSpec {

  val resourceInputPath = {
    getClass.getResource("/input").getPath() + "/"
  }

  def resourceOutputPath = {
    getClass.getResource("/output").getPath() + "/"
  }

  def build(builder: SAXBuilder)(fullPath: String) = {
    builder.build(new File(fullPath))
  }

  val mkDom = build(new SAXBuilder()) _

  def getListFromPath(file: File) = {
     val s = Source.fromFile(file)

     val items = (s.getLines().foldLeft(List[String]("")) {
       case (listAcc, line) =>
         if (line == "") {
           "" :: listAcc
         } else {
           (listAcc.head + line + "\n") :: listAcc.tail
         }
     }).filter(_ != "").map(_.dropRight(1)).reverse

     s.close

     items
  }

  private def getTspanSeq(dom: Document): Seq[Element] = {
    dom.getRootElement().getDescendants(new ElementFilter("tspan")).toIterable.filter(e => {
      e.getText().size > 0
    }).toIndexedSeq
  }

  def mkOutputSvgDom(inputPath: String): (Document) = {
    val inputName = inputPath.stripPrefix(resourceInputPath)
    val actualSvgPath = resourceOutputPath + inputName + ".actual.svg"
    assert(0 == Seq("bin/run.js", "--svg", "-i", inputPath, "-o", actualSvgPath).!)
    mkDom(actualSvgPath) 
  }

  Seq("find", resourceInputPath, "-type", "f").lines.foreach(inputPath => {

    val inputName = inputPath.stripPrefix(resourceInputPath)
    val tspanTextListFile = new File(
      resourceOutputPath + 
      inputName + ".expected/tspan-texts.txt"
    ) 

    if (tspanTextListFile.exists) {
      ("rub/bin.js with input: " + inputName) should "produce tspan text with spaces between words\n" +
      "and without space in the middle of words" in {

        val expectedTspanTexts = getListFromPath(tspanTextListFile).toIndexedSeq
        val actualTspanTexts = {
          val actualDom = mkOutputSvgDom(inputPath)
          val actualTspanSeq = getTspanSeq(actualDom)
          actualTspanSeq.map(_.getText()).toIndexedSeq
        }

        expectedTspanTexts.zipWithIndex.map(p => {
          val (expText, i) = p
          val actText = actualTspanTexts(i)
          assertResult(expText)(actText)
        })
      }

    }

  })


}
