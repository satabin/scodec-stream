package scodec.stream

import org.scalacheck._
import Prop._
import fs2.{ Fallible, Stream }
import scodec.Err
import scodec.bits._
import scodec.codecs._

object ScodecStreamSpec extends Properties("scodec.stream") {

  property("many/tryMany") = {
    forAll { (ints: List[Int]) =>
      val bits = vector(int32).encode(Vector.empty[Int] ++ ints).require
      val bits2 = StreamEncoder.many(int32).encodeAllValid(ints)
      bits == bits2 &&
        StreamDecoder.many(int32).decode[Fallible](Stream(bits)).toList == Right(ints) &&
        StreamDecoder.tryMany(int32).decode[Fallible](Stream(bits2)).toList == Right(ints)
    }
  }

  property("many/tryMany-insufficient") = secure {
    val bits = hex"00000001 00000002 0000".bits
    StreamDecoder.many(int32).decode[Fallible](Stream(bits)).toList == Right(List(1, 2))
    StreamDecoder.manyComplete(int32).decode[Fallible](Stream(bits)).toList == Left(CodecError(Err.InsufficientBits(32, 16, Nil)))
    StreamDecoder.tryMany(int32).decode[Fallible](Stream(bits)).toList == Right(List(1, 2))
  }

  property("tryMany-example") = secure {
    val bits = StreamEncoder.many(int32).encodeAllValid(Vector(1,2,3))
    StreamDecoder.tryMany(int32).decode[Fallible](Stream(bits)).toList == Right(List(1, 2, 3))
  }

  property("isolate") = forAll { (ints: List[Int], seed: Long) =>
    val bits = vector(int32).encode(ints.toVector).require
    val d =
      StreamDecoder.many(int32).isolate(bits.size).map(_ => 0) ++
      StreamDecoder.many(int32).isolate(bits.size).map(_ => 1)
    val s = Stream(bits ++ bits)
    d.decode[Fallible](s).toVector == Right(Vector.fill(ints.size)(0) ++ Vector.fill(ints.size.toInt)(1))
  }

  def genChunkSize = Gen.choose(1L, 128L)
  def genSmallListOfString = Gen.choose(0, 10).flatMap(n => Gen.listOfN(n, Gen.alphaStr))
  property("list-of-fixed-size-strings") = forAll(genSmallListOfString, genChunkSize) { (strings: List[String], chunkSize: Long) =>
    val bits = StreamEncoder.many(utf8_32).encodeAllValid(strings)
    val chunks = Stream.emits(bits.grouped(chunkSize).toSeq).covary[Fallible]
    chunks.through(StreamDecoder.many(utf8_32).toPipe).toList == Right(strings)
  }

  def genSmallListOfInt = Gen.choose(0, 10).flatMap(n => Gen.listOfN(n, Arbitrary.arbitrary[Int]))
  property("list-of-fixed-size-ints") = forAll(genSmallListOfInt, genChunkSize) { (ints: List[Int], chunkSize: Long) =>
    val bits = StreamEncoder.many(int32).encodeAllValid(ints)
    val chunks = Stream.emits(bits.grouped(chunkSize).toSeq).covary[Fallible]
    chunks.through(StreamDecoder.many(int32).toPipe).toList == Right(ints)
  }

  property("encode.emit") = forAll { (toEmit: Int, ints: List[Int]) =>
    val bv: BitVector = int32.encode(toEmit).require
    val e: StreamEncoder[Int] = StreamEncoder.emit[Int](bv)
    e.encode(Stream.emits(ints).covary[Fallible]).compile.fold(BitVector.empty)(_ ++ _) == Right(bv)
  }

  property("encode.tryOnce") = secure {
    (StreamEncoder.tryOnce(fail[Int](Err("error"))) ++ StreamEncoder.many(int8)).encode(Stream(1, 2).covary[Fallible]).toList == Right(List(hex"01".bits, hex"02".bits))
  }

  property("once-insufficient") = secure {
    val bits = hex"0000".bits
    StreamDecoder.once(int32).decode[Fallible](Stream(bits)).toList == Left(Nil)
    StreamDecoder.onceComplete(int32).decode[Fallible](Stream(bits)).toList == Left(CodecError(Err.InsufficientBits(32, 16, Nil)))
  }

}
