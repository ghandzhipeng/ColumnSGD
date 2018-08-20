package pku.mllibFP.classfication

import org.apache.spark.mllib.linalg.{DenseVector, SparseVector}
import pku.mllibFP.util.{ColumnMLDenseVectorException, LabeledPartDataPoint, MLUtils}
import org.apache.spark.rdd.RDD

import scala.util.Random

/**
  * in LR, the model size is 1*m, the intermediate result is 1*m, i.e., the dot product,
  * the loss is logistic loss.
  *
  * @param inputRDD      : dataRDD, each partition contains only one element, Array[IndexedDataPoint]
  * @param numFeatures
  * @param numPartitions : number of partitions for the model, e.g., number of tasks per stage
  * @param regParam
  * @param stepSize      : step size for batch
  * @param numIterations
  * @param miniBatchSize
  */

class LR(@transient inputRDD: RDD[Array[LabeledPartDataPoint]],
         numFeatures: Int,
         numPartitions: Int,
         regParam: Double,
         stepSize: Double,
         numIterations: Int,
         miniBatchSize: Int) extends BaseFPModel(inputRDD, numFeatures, numPartitions,
  regParam, stepSize, numIterations, miniBatchSize) {


  override def generateModel(inputRDD: RDD[Array[LabeledPartDataPoint]]): RDD[(Array[LabeledPartDataPoint],
    Array[Array[Double]])] = {
    // initialize intermediate results
    intermediateResults = Array.ofDim[Double](1, miniBatchSize)
    // generate model
    inputRDD.mapPartitions {
      iter => {
        val model: Array[Array[Double]] = Array.ofDim[Double](1, numFeatures / numPartitions + 1)
        Iterator((iter.next(), model))
      }
    }
  }


  override def computeBatchLoss(interResults: Array[Array[Double]], labels: Array[Double],
                                seed: Int): Double = {
    val rand = new Random(seed)
    var batchLoss: Double = 0
    val num_data_points = labels.length

    for (id_batch <- 0 until miniBatchSize) {
      val id_global = rand.nextInt(num_data_points)
      val label_scaled = 2 * labels(id_global) - 1
      batchLoss += MLUtils.log1pExp(-label_scaled * interResults(0)(id_batch))
    }
    batchLoss / miniBatchSize
  }


  override def computeInterResults(model: Array[Array[Double]], data_points: Array[LabeledPartDataPoint],
                                   new_seed: Int): Array[Array[Double]] = {
    val result: Array[Array[Double]] = Array.ofDim[Double](1, miniBatchSize)
    val rand = new Random(new_seed)
    val num_data_points = data_points.length
    for (id_batch <- 0 until miniBatchSize) {
      val id_global = rand.nextInt(num_data_points)
        data_points(id_global).features match {
        case sp: SparseVector => {
          val indices = sp.indices
          val values = sp.values
          for (idx <- 0 until indices.length) {
            result(0)(id_batch) += values(idx) * model(0)(indices(idx))
          }
        }
        case dp: DenseVector => {
          throw new ColumnMLDenseVectorException
        }
      }
    }
    result
  }


  override def updateModel(model: Array[Array[Double]], data_points: Array[LabeledPartDataPoint],
                           interResults: Array[Array[Double]], last_seed: Int): Unit = {
    val rand = new Random(last_seed)
    val num_data_points = data_points.length

    for (id_batch <- 0 until miniBatchSize) {
      val id_global = rand.nextInt(num_data_points)
      val tmp_data_point = data_points(id_global)
      val label_scaled = 2 * tmp_data_point.label - 1
      val coeff = -label_scaled / (1 + math.exp(label_scaled * interResults(0)(id_batch)))
      tmp_data_point.features match {
        case sp: SparseVector => {
          val indices = sp.indices
          val values = sp.values
          var i = 0
          while (i < indices.length) {
            model(0)(indices(i)) -= stepSize / miniBatchSize * coeff * values(i)
            i += 1
          }
        }
        case dp: DenseVector => {
          throw new ColumnMLDenseVectorException
        }
      }
    }
  }

}
