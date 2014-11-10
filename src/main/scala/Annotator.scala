package annotator

import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.Writer

import org.jdom2.Content
import org.jdom2.input.SAXBuilder
import org.jdom2.filter.ElementFilter
import org.jdom2.Element
import org.jdom2.Document
import org.jdom2.util.IteratorIterable

import scala.collection.JavaConversions.iterableAsScalaIterable 
import scala.collection.immutable.IntMap
import scala.collection.immutable.Queue
import scala.collection.immutable.HashMap

import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter
import org.jdom2.output.LineSeparator
import org.jdom2.output.support.AbstractXMLOutputProcessor

object Annotator {

  sealed trait Label
  case class B(c: Char) extends Label
  case object I extends Label
  case object O extends Label
  case object L extends Label
  case class U(c: Char) extends Label

  type Segment = IntMap[IntMap[Label]]

  case class AnnotationType(name: String, c: Char, constraintRange: ConstraintRange)

  type Element = org.jdom2.Element
  type ElementFilter = org.jdom2.filter.ElementFilter
  

  sealed trait Constraint
  case object CharCon extends Constraint
  case class SegmentCon(annotationTypeName: String) extends Constraint

  sealed trait ConstraintRange
  case class Range(from: Constraint, to: Constraint) extends ConstraintRange
  case class Single(constraint: Constraint) extends ConstraintRange

  case class AnnotationSpan(
    labelMap: IntMap[Label], 
    annotationTypeSeq: Seq[AnnotationType]
  )

  case class AnnotationInfo(annotationType: AnnotationType, bIndexTable: IntMap[List[Int]])

  case class AnnotationBlock(startIndex: Int, nextIndex: Int, annotationMap: Map[AnnotationType, AnnotationSpan])

  private def getElementsOf(dom: Document) = dom.getRootElement().getDescendants(new ElementFilter("tspan")).toIterable

}

import Annotator._
class Annotator(private val dom: Document, val annotationBlockSeq: IndexedSeq[AnnotationBlock], val annotationInfoMap: Map[String, AnnotationInfo]) {

  def this(dom: Document) = this(
    dom,
    getElementsOf(dom).foldLeft(IndexedSeq[AnnotationBlock]())( (seqAcc, e) => {
      val startIndex = if (seqAcc.isEmpty) 0 else seqAcc.last.nextIndex
      val nextIndex = startIndex + e.getText().size
      seqAcc :+ AnnotationBlock(startIndex, nextIndex, HashMap())
    } ),
    HashMap()
  )

  private val frozenDom = dom.clone()
  final def getElements() = getElementsOf(frozenDom.clone())
  private val frozenElements = getElements().toIndexedSeq

  private def renderAnnotation(a: AnnotationSpan, length: Int) = {

    val posi = (0 until length).foldLeft("")((stringAcc, i) => {
      stringAcc + (a.labelMap.get(i) match {
        case Some(B(char)) => char.toLower
        case Some(U(char)) => char.toUpper
        case Some(I) => '~'
        case Some(O) => '-'
        case Some(L) => '$'
        case None => ' '
      })
    })

    val constr =  ", constraint: " + {
      val constraintRange = a.annotationTypeSeq(0).constraintRange
      a.annotationTypeSeq.foreach(annoType => {
        assert(annoType.constraintRange == constraintRange)
      })
      def loop(cr: ConstraintRange): String = {
        cr match {
          case Single(CharCon) => 
            "char"
          case Single(SegmentCon(annotationTypeName)) => 
            annotationTypeName
          case Range(x, y) if x == y => 
            loop(Single(x))
          case Range(SegmentCon(annotationTypeName), end) => 
            val annotationType = annotationInfoMap(annotationTypeName).annotationType
            val con = annotationType.constraintRange match {
              case Single(c) => c
              case Range(_, c) => c
            }
            annotationTypeName + "." + loop(Range(con, end))
        }
      }
      loop(constraintRange)
    }

    val annot = {
      "type: " + "{" + a.annotationTypeSeq.map(at => {
        at.name + ": " + at.c
      }).mkString(", ") + "}"
    }

    "| |" + posi + "| " + "{" + annot + constr + "}"

  }

  private def renderAnnotationBlock(bb: AnnotationBlock): String = {
    val next = bb.nextIndex

    val height = (next - 1).toString.size

    val topRulerList = (height to 2 by -1).map(level => {
      "| |"+(bb.startIndex until next).map(i => {
        if (i == bb.startIndex || (i % 10) == 0){
          val divisor = Math.pow(10,level).toInt
          val digit = (i % divisor)/(divisor/10)
          if (digit == 0 && level == height) " " else digit
        } else " "
      }).mkString("")+"|"
    })

    val bottomRuler = "| |"+(bb.startIndex until next).map(_ % 10).mkString("")+"|"

    val ruler = (topRulerList :+ bottomRuler).mkString("\n")

    "\n" + bb.annotationMap.values.toList.distinct.map(renderAnnotation(_, (next - bb.startIndex))).mkString("\n") + "\n" + ruler + "\n "
  }


  private def addAnnotation(anno: AnnotationSpan, bb: AnnotationBlock) = { 
    //require(anno.labelMap.lastKey < bb.nextIndex)
    anno.annotationTypeSeq.foldLeft(bb)((_bb, annotationType) => {
      _bb.copy(annotationMap = _bb.annotationMap + (annotationType -> anno))
    })

  }

  private val charBIndexTable = IntMap(frozenElements.zipWithIndex.map { 
    case (e, blockIndex) => blockIndex -> (0 until e.getText().size).toList
  }: _*)


  final def getBIndexList(annotationTypeName: String): List[(Int,Int)] = {
    annotationInfoMap(annotationTypeName).bIndexTable.toList.flatMap {
      case (blockBIndex, charBIndexList) =>
        charBIndexList.map(charBIndex => {
          (blockBIndex, charBIndex)
        })
    }
  }


  final def getSegment(annotationTypeName: String)(blockIndex: Int, charIndex: Int): Segment = {

    val annotationType = annotationInfoMap(annotationTypeName).annotationType

    def loop(foundFirst: Boolean, blockIndex: Int, charIndex: Int): Segment = {

      if (annotationBlockSeq.size > blockIndex) {
        val block = annotationBlockSeq(blockIndex)
        block.annotationMap.get(annotationType) match {
          case None => loop(foundFirst, blockIndex + 1, 0)
          case Some(annotation) =>
            val labelMap = annotation.labelMap
            labelMap.keys.find(_ >= charIndex) match {
              case None =>
                loop(foundFirst, blockIndex + 1, 0)
              case Some(_charIndex) =>
                val label = labelMap(_charIndex)
                (foundFirst, label) match {
                  case (false, B(char)) if annotationType.c == char => loop(true, blockIndex, _charIndex) 
                  case (false, U(char)) if annotationType.c == char => loop(true, blockIndex, _charIndex) 
                  case (false, _) => loop(false, blockIndex, _charIndex + 1)

                  case (true, L) =>
                    IntMap(blockIndex -> IntMap(_charIndex -> L))
                  case (true, U(char)) if annotationType.c == char => 
                    IntMap(blockIndex -> IntMap(_charIndex -> U(char)))
                  case (true, U(_)) => 
                    loop(foundFirst, blockIndex, _charIndex + 1)
                  case (true, B(char)) if annotationType.c != char => 
                    loop(foundFirst, blockIndex, _charIndex + 1)
                  case (true, label) => 
                    val labelTable = loop(foundFirst, blockIndex, _charIndex + 1)
                    labelTable.get(blockIndex) match {
                      case None => 
                        labelTable + (blockIndex -> IntMap(_charIndex -> label))
                      case Some(rowIntMap) => 
                        labelTable + (blockIndex -> (rowIntMap + (_charIndex -> label)))
                    }
                }
            }
        }
      } else {
        IntMap[IntMap[Label]]()
      }

    }

    loop(false, blockIndex, charIndex)

  }

  final def getElementsInRange(blockIndex1: Int, blockIndex2: Int): IntMap[Element] = {
    IntMap((blockIndex1 to blockIndex2).map(blockIndex =>{
      blockIndex -> frozenElements(blockIndex).clone()
    }): _*)
  }

  final def getElements(annotationTypeName: String)(blockIndex: Int, charIndex: Int): IntMap[Element] = {
    val segment = getSegment(annotationTypeName)(blockIndex, charIndex)
    val blockBIndex = segment.firstKey
    val blockLIndex = segment.lastKey
    getElementsInRange(blockBIndex, blockLIndex)
  }

  final def getTextMapInRange(blockIndex1: Int, charIndex1: Int, blockIndex2: Int, charIndex2: Int): IntMap[String] = {
    getElementsInRange(blockIndex1, blockIndex2).map {
      case (blockIndex, e) if blockIndex == blockIndex1 =>
        blockIndex -> e.getText().drop(charIndex1)
      case (blockIndex, e) if blockIndex == blockIndex2 =>
        blockIndex -> e.getText().take(charIndex2 + 1)
      case (blockIndex, e) => 
        blockIndex -> e.getText()
    }
  }

  final def getTextMap(annotationTypeName: String)(blockIndex: Int, charIndex: Int): IntMap[String] = {
    val segment = getSegment(annotationTypeName)(blockIndex, charIndex)

    val blockBIndex = segment.firstKey
    val charBIndex = segment(blockBIndex).firstKey
    val blockLIndex = segment.lastKey
    val charLIndex = segment(segment.lastKey).lastKey

    getTextMapInRange(
        blockBIndex, 
        charBIndex,
        blockLIndex,
        charLIndex
    )
  }

  private def filterStartIndexes(char: Char, blockIndex: Int, charIndexList: Iterable[Int], rule: (Int, Int) => Option[Label]) = {
    charIndexList.flatMap(charIndex => {
      rule(blockIndex, charIndex) match {
        case Some(label) if(label == B(char) || label == U(char)) =>
          Some(charIndex)
        case _ => None
      }
    }).toList match {
      case Nil => None
      case xs => Some(blockIndex -> xs)
    }
  }

  private def getAnnotatableIndexTable(constraintRange: ConstraintRange) = {
    constraintRange match {
      case Single(CharCon) =>
        charBIndexTable
      case Single(SegmentCon(annotationTypeName)) =>
        annotationInfoMap(annotationTypeName).bIndexTable
      case Range(startCon, endCon) =>
        def loop(bIndexTableAcc: IntMap[List[Int]], constraint: Constraint): IntMap[List[Int]] = {
          (constraint, endCon) match {
            case (CharCon, SegmentCon(_)) => 
              //invalid: charcon should not exist before end of range
              IntMap[List[Int]]()
            case (x, y) if (x == y) => 
              bIndexTableAcc
            case (SegmentCon(annotationTypeName), _) =>
              val _bIndexTableAcc = getBIndexList(annotationTypeName).foldLeft(IntMap[List[Int]]())((acc, pair) => { 
                val (blockIndex, charIndex) = pair
                if (bIndexTableAcc.contains(blockIndex) && bIndexTableAcc(blockIndex).contains(charIndex)) {

                  val segment = getSegment(annotationTypeName)(blockIndex, charIndex)
                  segment.foldLeft(acc) {
                    case (_acc, (bI, labelMap)) =>
                      _acc.get(bI) match {
                        case None => _acc + (bI -> labelMap.keys.toList)
                        case Some(charIndexList) => _acc + (bI -> (charIndexList ++ labelMap.keys))
                      }
                  }
                } else {
                  acc
                }
              })

              val annotationType = annotationInfoMap(annotationTypeName).annotationType

              val _constraint = annotationType.constraintRange match {
                case Single(c) => c
                case Range(_, c) => c
              }

              loop(_bIndexTableAcc, _constraint)
          }
        }
        loop(charBIndexTable, startCon)
    }
  }


  final def annotate(
      nameCharPairSeq: Seq[(String, Char)], 
      constraintRange: ConstraintRange, 
      rule: (Int, Int) => Option[Label]
  ) = {

    val annotatableIndexTable = getAnnotatableIndexTable(constraintRange)

    val annotationTypeSeq = nameCharPairSeq.map {
      case (name, char) =>
        AnnotationType(name, char, constraintRange)
    }

    val _annotationBlockSeq = annotationBlockSeq.zipWithIndex.map { case (block, blockIndex) => {
      annotatableIndexTable.get(blockIndex) match {
        case None => block
        case Some(charIndexList) =>
          val labelMap = IntMap(charIndexList.flatMap(charIndex => {
            rule(blockIndex, charIndex).map((charIndex -> _))
          }): _*)
          val annotation = AnnotationSpan(labelMap, annotationTypeSeq)
          addAnnotation(annotation, block)
      }
    }}

    val _annotationInfoMap =  {
      val annotationInfoList = annotationTypeSeq.map {
        case _annotationType => 
          val bIndexTable = annotatableIndexTable.flatMap {
            case (blockIndex, charIndexList) => 
              filterStartIndexes(_annotationType.c, blockIndex, charIndexList, rule)
          }
          _annotationType.name -> AnnotationInfo(_annotationType, bIndexTable)
      }

      annotationInfoMap ++ annotationInfoList
    }

    new Annotator(
      frozenDom,
      _annotationBlockSeq,
      _annotationInfoMap
    )
    
  }

  private val xmlOutputProcessor = new AbstractXMLOutputProcessor {
    override def write(writer: Writer, str: String) = {
      super.write(
          writer, 
          if (str == null) {
            str
          } else {
            str.replaceAll("&#xA;", "\n").replaceAll("<svg:tspan", "\n<svg:tspan")
          }
      )
    }
  }

  final def write(filePath: String): Annotator = {

    val writableDom = frozenDom.clone()
    getElementsOf(writableDom).zipWithIndex.foreach { case (e, i) => {
      val block = annotationBlockSeq(i)
      e.setAttribute("bio", renderAnnotationBlock(block))
    }}

    //format
    val outputter = new XMLOutputter(Format.getPrettyFormat(), xmlOutputProcessor)

    //write
    val out = new FileOutputStream(filePath)
    outputter.output(writableDom, out)
    this

  }

}
