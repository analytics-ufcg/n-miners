package api

import java.io.IOException
import java.util.{Comparator, Arrays, Random}

import com.google.common.base.Preconditions
import com.google.common.primitives.Ints
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.IntWritable
import org.apache.hadoop.mapreduce.{Mapper, Reducer}
import org.apache.mahout.common.{ClassUtils, RandomUtils}
import org.apache.mahout.math.Vector.Element
import org.apache.mahout.math.hadoop.similarity.cooccurrence.{MutableElement, TopElementsQueue, Vectors}
import org.apache.mahout.math.hadoop.similarity.cooccurrence.measures.VectorSimilarityMeasure
import org.apache.mahout.math.map.OpenIntIntHashMap
import org.apache.mahout.math.{VectorWritable, RandomAccessSparseVector, Vector}
import utils.Implicits._

class RowSimilarityJob {
  val NO_THRESHOLD: Double = Double.MinValue
  val NO_FIXED_RANDOM_SEED: Long = Long.MinValue
  val SIMILARITY_CLASSNAME: String = classOf[RowSimilarityJob] + ".distributedSimilarityClassname"
  val NUMBER_OF_COLUMNS: String = classOf[RowSimilarityJob] + ".numberOfColumns"
  val MAX_SIMILARITIES_PER_ROW: String = classOf[RowSimilarityJob] + ".maxSimilaritiesPerRow"
  val EXCLUDE_SELF_SIMILARITY: String = classOf[RowSimilarityJob] + ".excludeSelfSimilarity"
  val THRESHOLD: String = classOf[RowSimilarityJob] + ".threshold"
  val NORMS_PATH: String = classOf[RowSimilarityJob] + ".normsPath"
  val MAXVALUES_PATH: String = classOf[RowSimilarityJob] + ".maxWeightsPath"
  val NUM_NON_ZERO_ENTRIES_PATH: String = classOf[RowSimilarityJob] + ".nonZeroEntriesPath"
  val DEFAULT_MAX_SIMILARITIES_PER_ROW: Int = 100
  val OBSERVATIONS_PER_COLUMN_PATH: String = classOf[RowSimilarityJob] + ".observationsPerColumnPath"
  val MAX_OBSERVATIONS_PER_ROW: String = classOf[RowSimilarityJob] + ".maxObservationsPerRow"
  val MAX_OBSERVATIONS_PER_COLUMN: String = classOf[RowSimilarityJob] + ".maxObservationsPerColumn"
  val RANDOM_SEED: String = classOf[RowSimilarityJob] + ".randomSeed"
  val DEFAULT_MAX_OBSERVATIONS_PER_ROW: Int = 500
  val DEFAULT_MAX_OBSERVATIONS_PER_COLUMN: Int = 500
  val NORM_VECTOR_MARKER: Int = Integer.MIN_VALUE
  val MAXVALUE_VECTOR_MARKER: Int = Integer.MIN_VALUE + 1
  val NUM_NON_ZERO_ENTRIES_VECTOR_MARKER: Int = Integer.MIN_VALUE + 2


  private[cooccurrence] object Counters extends Enumeration {
    type Counters = Value
    val ROWS, USED_OBSERVATIONS, NEGLECTED_OBSERVATIONS, COOCCURRENCES, PRUNED_COOCCURRENCES = Value
  }

  class VectorNormMapper extends Mapper[IntWritable, VectorWritable, IntWritable, VectorWritable] {
    var similarity: VectorSimilarityMeasure = null
    var norms: Vector = null
    var nonZeroEntries: Vector = null
    var maxValues: Vector = null
    var threshold: Double = .0
    var observationsPerColumn: OpenIntIntHashMap = null
    var maxObservationsPerRow: Int = 0
    var maxObservationsPerColumn: Int = 0
    var random: Random = null


    @throws(classOf[IOException])
    @throws(classOf[InterruptedException])
    override def setup(ctx: Mapper[IntWritable, VectorWritable, IntWritable, VectorWritable]#Context) {
      val conf: Configuration = ctx.getConfiguration
      similarity = ClassUtils.instantiateAs(conf.get(SIMILARITY_CLASSNAME), classOf[VectorSimilarityMeasure])
      norms = new RandomAccessSparseVector(Integer.MAX_VALUE)
      nonZeroEntries = new RandomAccessSparseVector(Integer.MAX_VALUE)
      maxValues = new RandomAccessSparseVector(Integer.MAX_VALUE)
      threshold = conf.get(THRESHOLD).toDouble
      observationsPerColumn = Vectors.readAsIntMap(new Path(conf.get(OBSERVATIONS_PER_COLUMN_PATH)), conf)
      maxObservationsPerRow = conf.getInt(MAX_OBSERVATIONS_PER_ROW, DEFAULT_MAX_OBSERVATIONS_PER_ROW)
      maxObservationsPerColumn = conf.getInt(MAX_OBSERVATIONS_PER_COLUMN, DEFAULT_MAX_OBSERVATIONS_PER_COLUMN)
      val seed: Long = conf.get(RANDOM_SEED).toLong
      if (seed == NO_FIXED_RANDOM_SEED) {
        random = RandomUtils.getRandom
      }
      else {
        random = RandomUtils.getRandom(seed)
      }
    }

    private def sampleDown(rowVector: Vector, ctx: Mapper[IntWritable, VectorWritable, IntWritable, VectorWritable]#Context): Vector = {
      val observationsPerRow: Int = rowVector.getNumNondefaultElements
      val rowSampleRate: Double = Math.min(maxObservationsPerRow, observationsPerRow).toDouble / observationsPerRow.toDouble
      val downsampledRow: Vector = rowVector.like
      var usedObservations: Long = 0
      var neglectedObservations: Long = 0
      //      import scala.collection.JavaConversions._
      for (elem <- rowVector.nonZeroes) {
        val columnCount: Int = observationsPerColumn.get(elem.index)
        val columnSampleRate: Double = Math.min(maxObservationsPerColumn, columnCount).toDouble / columnCount.toDouble
        if (random.nextDouble <= Math.min(rowSampleRate, columnSampleRate)) {
          downsampledRow.setQuick(elem.index, elem.get)
          usedObservations += 1
        }
        else {
          neglectedObservations += 1
        }
      }
      /*   ctx.getCounter(Counters.USED_OBSERVATIONS).increment(usedObservations)
      ctx.getCounter(Counters.NEGLECTED_OBSERVATIONS).increment(neglectedObservations)*/
      return downsampledRow
    }

    @throws(classOf[IOException])
    @throws(classOf[InterruptedException])
    protected override def map(row: IntWritable, vectorWritable: VectorWritable, ctx: Mapper[IntWritable, VectorWritable, IntWritable, VectorWritable]#Context) {
      val sampledRowVector: Vector = sampleDown(vectorWritable.get, ctx)
      val rowVector: Vector = similarity.normalize(sampledRowVector)
      var numNonZeroEntries: Int = 0
      var maxValue: Double = Double.MinValue
      //      import scala.collection.JavaConversions._
      for (element <- rowVector.nonZeroes) {
        val partialColumnVector: RandomAccessSparseVector = new RandomAccessSparseVector(Integer.MAX_VALUE)
        partialColumnVector.setQuick(row.get, element.get)
        ctx.write(new IntWritable(element.index), new VectorWritable(partialColumnVector))
        numNonZeroEntries += 1
        if (maxValue < element.get) {
          maxValue = element.get
        }
      }
      if (threshold != NO_THRESHOLD) {
        nonZeroEntries.setQuick(row.get, numNonZeroEntries)
        maxValues.setQuick(row.get, maxValue)
      }
      norms.setQuick(row.get, similarity.norm(rowVector))
      //      ctx.getCounter(Counters.ROWS).increment(1)
    }

    @throws(classOf[IOException])
    @throws(classOf[InterruptedException])
    protected override def cleanup(ctx: Mapper[IntWritable, VectorWritable, IntWritable, VectorWritable]#Context) {
      ctx.write(new IntWritable(NORM_VECTOR_MARKER), new VectorWritable(norms))
      ctx.write(new IntWritable(NUM_NON_ZERO_ENTRIES_VECTOR_MARKER), new VectorWritable(nonZeroEntries))
      ctx.write(new IntWritable(MAXVALUE_VECTOR_MARKER), new VectorWritable(maxValues))
    }
  }


  class MergeVectorsReducer extends Reducer[IntWritable, VectorWritable, IntWritable, VectorWritable] {
    var normsPath: Path = null
    var numNonZeroEntriesPath: Path = null
    var maxValuesPath: Path = null

    @throws(classOf[IOException])
    @throws(classOf[InterruptedException])
    protected override def setup(ctx: Reducer[IntWritable, VectorWritable, IntWritable, VectorWritable]#Context) {
      normsPath = new Path(ctx.getConfiguration.get(NORMS_PATH))
      numNonZeroEntriesPath = new Path(ctx.getConfiguration.get(NUM_NON_ZERO_ENTRIES_PATH))
      maxValuesPath = new Path(ctx.getConfiguration.get(MAXVALUES_PATH))
    }

    @throws(classOf[IOException])
    @throws(classOf[InterruptedException])
    protected override def reduce(row: IntWritable, partialVectors: java.lang.Iterable[VectorWritable], ctx: Reducer[IntWritable, VectorWritable, IntWritable, VectorWritable]#Context) {
      val partialVector: Vector = Vectors.merge(partialVectors)
      if (row.get == NORM_VECTOR_MARKER) {
        Vectors.write(partialVector, normsPath, ctx.getConfiguration)
      }
      else if (row.get == MAXVALUE_VECTOR_MARKER) {
        Vectors.write(partialVector, maxValuesPath, ctx.getConfiguration)
      }
      else if (row.get == NUM_NON_ZERO_ENTRIES_VECTOR_MARKER) {
        Vectors.write(partialVector, numNonZeroEntriesPath, ctx.getConfiguration, true)
      }
      else {
        ctx.write(row, new VectorWritable(partialVector))
      }
    }
  }

  object CooccurrencesMapper {
    val BY_INDEX: Comparator[Vector.Element] = new Comparator[Element] {
      override def compare(one: Element, two: Element): Int = {
        return Ints.compare(one.index, two.index)
      }
    }

    class CooccurrencesMapper extends Mapper[IntWritable, VectorWritable, IntWritable, VectorWritable] {
      var similarity: VectorSimilarityMeasure = null
      var numNonZeroEntries: OpenIntIntHashMap = null
      var maxValues: Vector = null
      var threshold: Double = .0

      @throws(classOf[IOException])
      @throws(classOf[InterruptedException])
      protected override def setup(ctx: Mapper[IntWritable, VectorWritable, IntWritable, VectorWritable]#Context) {
        similarity = ClassUtils.instantiateAs(ctx.getConfiguration.get(SIMILARITY_CLASSNAME), classOf[VectorSimilarityMeasure])
        numNonZeroEntries = Vectors.readAsIntMap(new Path(ctx.getConfiguration.get(NUM_NON_ZERO_ENTRIES_PATH)), ctx.getConfiguration)
        maxValues = Vectors.read(new Path(ctx.getConfiguration.get(MAXVALUES_PATH)), ctx.getConfiguration)
        threshold = ctx.getConfiguration.get(THRESHOLD).toDouble
      }

      private def consider(occurrenceA: Vector.Element, occurrenceB: Vector.Element): Boolean = {
        val numNonZeroEntriesA: Int = numNonZeroEntries.get(occurrenceA.index)
        val numNonZeroEntriesB: Int = numNonZeroEntries.get(occurrenceB.index)
        val maxValueA: Double = maxValues.get(occurrenceA.index)
        val maxValueB: Double = maxValues.get(occurrenceB.index)
        return similarity.consider(numNonZeroEntriesA, numNonZeroEntriesB, maxValueA, maxValueB, threshold)
      }

      @throws(classOf[IOException])
      @throws(classOf[InterruptedException])
      protected override def map(column: IntWritable, occurrenceVector: VectorWritable, ctx: Mapper[IntWritable, VectorWritable, IntWritable, VectorWritable]#Context) {
        val occurrences: Array[Vector.Element] = Vectors.toArray(occurrenceVector)
        Arrays.sort(occurrences, CooccurrencesMapper.BY_INDEX)
        var cooccurrences: Int = 0
        var prunedCooccurrences: Int = 0
        for (n: Int <- 0 to occurrences.length) {
          print(n)
          val occurrenceA: Vector.Element = occurrences(n);
          val dots: Vector = new RandomAccessSparseVector(Integer.MAX_VALUE);
          for (m: Int <- n to occurrences.length) {
            val occurrenceB:Vector.Element = occurrences(m);
            if (threshold == NO_THRESHOLD || consider(occurrenceA, occurrenceB)) {
              dots.setQuick(occurrenceB.index(), similarity.aggregate(occurrenceA.get(), occurrenceB.get()));
              cooccurrences = cooccurrences+1;
            } else {
              prunedCooccurrences = prunedCooccurrences+1;
            }
          }
          ctx.write(new IntWritable(occurrenceA.index()), new VectorWritable(dots));
        }
        /*ctx.getCounter(Counters.COOCCURRENCES).increment(cooccurrences)
        ctx.getCounter(Counters.PRUNED_COOCCURRENCES).increment(prunedCooccurrences)*/
      }
    }

  }

  class SimilarityReducer extends Reducer[IntWritable, VectorWritable, IntWritable, VectorWritable] {
    private var similarity: VectorSimilarityMeasure = null
    private var numberOfColumns: Int = 0
    private var excludeSelfSimilarity: Boolean = false
    private var norms: Vector = null
    private var treshold: Double = .0

    @throws(classOf[IOException])
    @throws(classOf[InterruptedException])
    protected override def setup(ctx: Reducer[IntWritable, VectorWritable, IntWritable, VectorWritable]#Context) {
      similarity = ClassUtils.instantiateAs(ctx.getConfiguration.get(SIMILARITY_CLASSNAME), classOf[VectorSimilarityMeasure])
      numberOfColumns = ctx.getConfiguration.getInt(NUMBER_OF_COLUMNS, -1)
      Preconditions.checkArgument(numberOfColumns > 0, "Number of columns must be greater then 0! But numberOfColumns = " + numberOfColumns)
      excludeSelfSimilarity = ctx.getConfiguration.getBoolean(EXCLUDE_SELF_SIMILARITY, false)
      norms = Vectors.read(new Path(ctx.getConfiguration.get(NORMS_PATH)), ctx.getConfiguration)
      treshold = ctx.getConfiguration.get(THRESHOLD).toDouble
    }

    @throws(classOf[IOException])
    @throws(classOf[InterruptedException])
    override def reduce(row: IntWritable, partialDots: java.lang.Iterable[VectorWritable], ctx: Reducer[IntWritable, VectorWritable, IntWritable, VectorWritable]#Context) {
      val partialDotsIterator: Iterator[VectorWritable] = partialDots.iterator
      val dots: Vector = partialDotsIterator.next.get
      while (partialDotsIterator.hasNext) {
        val toAdd: Vector = partialDotsIterator.next.get
        for (nonZeroElement <- toAdd.nonZeroes) {
          dots.setQuick(nonZeroElement.index, dots.getQuick(nonZeroElement.index) + nonZeroElement.get)
        }
      }
      val similarities: Vector = dots.like
      val normA: Double = norms.getQuick(row.get)
      for (b <- dots.nonZeroes) {
        val similarityValue: Double = similarity.similarity(b.get, normA, norms.getQuick(b.index), numberOfColumns)
        if (similarityValue >= treshold) {
          similarities.set(b.index, similarityValue)
        }
      }
      if (excludeSelfSimilarity) {
        similarities.setQuick(row.get, 0)
      }
      ctx.write(row, new VectorWritable(similarities))
    }
  }

  class UnsymmetrifyMapper extends Mapper[IntWritable, VectorWritable, IntWritable, VectorWritable] {
    private var maxSimilaritiesPerRow: Int = 0

    @throws(classOf[IOException])
    @throws(classOf[InterruptedException])
    protected override def setup(ctx: Mapper[IntWritable, VectorWritable, IntWritable, VectorWritable]#Context) {
      maxSimilaritiesPerRow = ctx.getConfiguration.getInt(MAX_SIMILARITIES_PER_ROW, 0)
      Preconditions.checkArgument(maxSimilaritiesPerRow > 0, "Maximum number of similarities per row must be greater then 0!")
    }

    @throws(classOf[IOException])
    @throws(classOf[InterruptedException])
    protected override def map(row: IntWritable, similaritiesWritable: VectorWritable, ctx: Mapper[IntWritable, VectorWritable, IntWritable, VectorWritable]#Context) {
      val similarities: Vector = similaritiesWritable.get
      val transposedPartial: Vector = new RandomAccessSparseVector(similarities.size, 1)
      val topKQueue: TopElementsQueue = new TopElementsQueue(maxSimilaritiesPerRow)
      for (nonZeroElement <- similarities.nonZeroes) {
        val top: MutableElement = topKQueue.top
        val candidateValue: Double = nonZeroElement.get
        if (candidateValue > top.get) {
          top.setIndex(nonZeroElement.index)
          top.set(candidateValue)
          topKQueue.updateTop
        }
        transposedPartial.setQuick(row.get, candidateValue)
        ctx.write(new IntWritable(nonZeroElement.index), new VectorWritable(transposedPartial))
        transposedPartial.setQuick(row.get, 0.0)
      }
      val topKSimilarities: Vector = new RandomAccessSparseVector(similarities.size, maxSimilaritiesPerRow)
      import scala.collection.JavaConversions._
      for (topKSimilarity <- topKQueue.getTopElements) {
        topKSimilarities.setQuick(topKSimilarity.index, topKSimilarity.get)
      }
      ctx.write(row, new VectorWritable(topKSimilarities))
    }
  }

  class MergeToTopKSimilaritiesReducer extends Reducer[IntWritable, VectorWritable, IntWritable, VectorWritable] {
    private var maxSimilaritiesPerRow: Int = 0

    @throws(classOf[IOException])
    @throws(classOf[InterruptedException])
    protected override def setup(ctx: Reducer[IntWritable, VectorWritable, IntWritable, VectorWritable]#Context) {
      maxSimilaritiesPerRow = ctx.getConfiguration.getInt(MAX_SIMILARITIES_PER_ROW, 0)
      Preconditions.checkArgument(maxSimilaritiesPerRow > 0, "Maximum number of similarities per row must be greater then 0!")
    }

    @throws(classOf[IOException])
    @throws(classOf[InterruptedException])
    protected override def reduce(row: IntWritable, partials:  java.lang.Iterable[VectorWritable], ctx: Reducer[IntWritable, VectorWritable, IntWritable, VectorWritable]#Context) {
      val allSimilarities: Vector = Vectors.merge(partials)
      val topKSimilarities: Vector = Vectors.topKElements(maxSimilaritiesPerRow, allSimilarities)
      ctx.write(row, new VectorWritable(topKSimilarities))
    }
  }

}
