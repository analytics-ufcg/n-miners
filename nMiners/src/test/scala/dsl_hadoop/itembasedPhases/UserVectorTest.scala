package dsl_spark.itembasedPhases  //    UserVectorDriver.start()


import java.io.File

import com.typesafe.config.ConfigFactory
import dsl_spark.job.{user_vectors, parse_data}
import org.scalatest.{FlatSpec, Matchers}
import dsl_spark.job.Implicits._

class UserVectorTest extends FlatSpec with Matchers{
  val BASE_PHATH = "src/test/data/"
  val config = ConfigFactory.load()

  "user_vector" should "run" in {
    val dataSet = "src/test/resources/data_1/actions.csv"
    val outputPath: String = "src/test/resources/DSL_Tests/users_vectors/"

    parse_data on dataSet then
      dsl_spark.job.JobUtils.produce(user_vectors) then dsl_spark.job.execute
  }

  "user_vector" should "write" in {
    val dataSet = "src/test/resources/data_1/actions.csv"
    val outputPath: String = "src/test/resources/DSL_Tests/users_vectors/"

    parse_data on dataSet then
      dsl_spark.job.JobUtils.produce(user_vectors) write_on outputPath then dsl_spark.job.execute

    fileExists(new File(outputPath)) should be equals true
    delete(new File(outputPath)) should be equals true
    fileExists(new File(outputPath)) should be equals false

  }

  it should "in 2 process" in {
//    val dataSet = "src/test/resources/data_2/input_test_user_vector.txt"
//    val outputPath: String = "src/test/resources/SimplePhasesTest/output_sim/"
//
//    parse_data on dataSet then
//    dsl.job.JobUtils.produce(user_vectors) in (2 process) write_on outputPath then dsl.job.execute

  }

  it should "associate to a variable" in {
    val dataSet = "src/test/resources/data_1/actions.csv"
    val outputPath: String = "src/test/resources/DSL_Tests/users_vectors/"

    parse_data on dataSet then
      dsl_spark.job.JobUtils.produce(user_vectors as "matrix") write_on outputPath then dsl_spark.job.execute

    fileExists(new File(outputPath)) should be equals true
    delete(new File(outputPath)) should be equals true
    fileExists(new File(outputPath)) should be equals false

  }

  it should "associate to a variable and in 2 process" in {
//    val dataSet = "src/test/resources/data_2/input_test_user_vector.txt"
//    val outputPath: String = "src/test/resources/SimplePhasesTest/output_sim/"
//
//    parse_data on dataSet then
//      dsl.job.JobUtils.produce(user_vectors as "user_vec") in (2 process)  write_on outputPath then dsl.job.execute

  }

  def delete(file: File):Boolean ={
    if (file.isDirectory)
      Option(file.listFiles).map(_.toList).getOrElse(Nil).foreach(delete(_))
    return file.delete
  }

  def fileExists(file: File): Boolean ={
    file.exists()
  }


}
