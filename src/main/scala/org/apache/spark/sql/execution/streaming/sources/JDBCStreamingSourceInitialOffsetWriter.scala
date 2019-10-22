package org.apache.spark.sql.execution.streaming.sources

import java.io._
import java.nio.charset.StandardCharsets

import org.apache.commons.io.IOUtils
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.execution.streaming.{HDFSMetadataLog, LongOffset, SerializedOffset}

class JDBCStreamingSourceInitialOffsetWriter(sparkSession: SparkSession, metadataPath: String)
    extends HDFSMetadataLog[LongOffset](sparkSession, metadataPath) {

  val VERSION = 1

  private def validateVersion(text: String, maxSupportedVersion: Int): Int = {
    if (text.length > 0 && text(0) == 'v') {
      val version =
        try {
          text.substring(1, text.length).toInt
        } catch {
          case _: NumberFormatException =>
            throw new IllegalStateException(
              s"Log file was malformed: failed to read correct log " +
                s"version from $text."
            )
        }
      if (version > 0) {
        if (version > maxSupportedVersion) {
          throw new IllegalStateException(
            s"UnsupportedLogVersion: maximum supported log version " +
              s"is v$maxSupportedVersion, but encountered v$version. The log file was produced " +
              s"by a newer version of Spark and cannot be read by this version. Please upgrade."
          )
        } else {
          return version
        }
      }
    }

    // reaching here means we failed to read the correct log version
    throw new IllegalStateException(
      s"Log file was malformed: failed to read correct log " +
        s"version from $text."
    )
  }

  override def serialize(metadata: LongOffset, out: OutputStream): Unit = {
    out.write(0) // A zero byte is written to support Spark 2.1.0 (SPARK-19517)
    val writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))
    writer.write(s"v$VERSION\n")
    writer.write(metadata.json)
    writer.flush()
  }

  override def deserialize(in: InputStream): LongOffset = {
    in.read() // A zero byte is read to support Spark 2.1.0 (SPARK-19517)
    val content = IOUtils.toString(new InputStreamReader(in, StandardCharsets.UTF_8))
    // HDFSMetadataLog guarantees that it never creates a partial file.
    require(content.nonEmpty)
    if (content(0) == 'v') {
      val indexOfNewLine = content.indexOf("\n")
      if (indexOfNewLine > 0) {
        validateVersion(content.substring(0, indexOfNewLine), VERSION)
        LongOffset(SerializedOffset(content.substring(indexOfNewLine + 1)))
      } else {
        throw new IllegalStateException("Log file was malformed: failed to detect the log file version line.")
      }
    } else {
      // The log was generated by Spark 2.1.0
      LongOffset(SerializedOffset(content))
    }
  }
}
