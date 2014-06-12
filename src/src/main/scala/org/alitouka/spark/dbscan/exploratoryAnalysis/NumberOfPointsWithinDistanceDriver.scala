package org.alitouka.spark.dbscan.exploratoryAnalysis

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.alitouka.spark.dbscan.util.io.IOHelper
import org.alitouka.spark.dbscan.DbscanSettings
import ExploratoryAnalysisHelper._
import org.alitouka.spark.dbscan.util.commandLine._
import org.alitouka.spark.dbscan.spatial.DistanceAnalyzer
import org.alitouka.spark.dbscan.spatial.rdd.{PartitioningSettings, PointsPartitionedByBoxesRDD}
import org.alitouka.spark.dbscan.util.debug.Clock

/** A driver program which calculates number of point's neighbors within specified distance
  * and generates a histogram of distribution of this number across the data set
  *
  */
object NumberOfPointsWithinDistanceDriver {

  private [dbscan] class Args extends CommonArgs with NumberOfBucketsArg with EpsArg with NumberOfPointsInPartitionArg

  private [dbscan] class ArgsParser
    extends CommonArgsParser (new Args (), "NumberOfPointsWithinDistanceDriver")
    with NumberOfBucketsArgParsing [Args]
    with EpsArgParsing[Args]
    with NumberOfPointsInPartitionParsing[Args]


  def main (args: Array[String]) {
    val argsParser = new ArgsParser ()

    if (argsParser.parse(args)) {
      val clock = new Clock ()
      val distance = argsParser.args.eps // args(2).toDouble

      val sc = new SparkContext(argsParser.args.masterUrl,
        "Histogram of number of points within " + distance + " from each point",
        jars = Array(argsParser.args.jar))

      val data = IOHelper.readDataset(sc, argsParser.args.inputPath)

      val settings = new DbscanSettings()
        .withEpsilon(distance)
        .withDistanceMeasure(argsParser.args.distanceMeasure)

      val partitioningSettings = new PartitioningSettings (numberOfPointsInBox = argsParser.args.numberOfPoints)

      val partitionedData = PointsPartitionedByBoxesRDD (data, partitioningSettings, settings)
      val distanceAnalyzer = new DistanceAnalyzer(settings)
      val closePoints = distanceAnalyzer.findClosePoints(partitionedData, true)

      val countsOfPointsWithNeighbors = closePoints
        .map(x => (x._1.pointId, 1L))
        .reduceByKey(_ + _)
        .map(x  => (x._1, x._2 + 1)) // +1 to include the point itself
        .cache()

      val indexedPoints = PointsPartitionedByBoxesRDD.extractPointIdsAndCoordinates (partitionedData)

      val countsOfPointsWithoutNeighbors = indexedPoints
        .keys
        .subtract(countsOfPointsWithNeighbors.keys)
        .map((_, 0L))

      val allCounts = countsOfPointsWithNeighbors union countsOfPointsWithoutNeighbors

      val histogram = ExploratoryAnalysisHelper.calculateHistogram(
        allCounts,
        argsParser.args.numberOfBuckets)

      val triples = ExploratoryAnalysisHelper.convertHistogramToTriples(histogram)

      IOHelper.saveTriples(sc.parallelize(triples), argsParser.args.outputPath)

      clock.logTimeSinceStart("Calculation of number of points within " + distance)
    }
  }

}