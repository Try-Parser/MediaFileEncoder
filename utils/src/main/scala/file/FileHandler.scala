package utils.file

import java.util.UUID
import java.time.Instant
import java.io.{File, FileNotFoundException}
import java.nio.file.{Files, Path, Paths}

import com.typesafe.config.{Config, ConfigFactory}

case class FileHandler(config: Config) {
	lazy val basePath: String = config.getString("file-directory.base-path")
	lazy val uploadFilePath: String = config.getString("file-directory.upload.path")
	lazy val maxContentSize: Long  = config.getLong("file-directory.upload.max-content-size")
	lazy val convertFilePath: String = config.getString("file-directory.convert.path")

	def getFile(fileName: String, upload: Boolean = true): File = 
		new File(s"$basePath/${if(upload) uploadFilePath else convertFilePath}/$fileName")

	def getExt(fileName: String): String = {
		val fullName: Array[String] = fileName.split("\\.")
		if(fullName.size <= 1) "tmp" else fullName(fullName.size-1)
	}

	def generateName(oldName: String): String = 
		s"${UUID.randomUUID.toString}-${Instant.now.getEpochSecond.toString}.${getExt(oldName)}"

	def generateName(oldName: String, ext: String): String = 
		s"${UUID.randomUUID.toString}-${Instant.now.getEpochSecond.toString}.$ext"

	def getPath(fileName: String): Either[Throwable, Path] = {
		val uploadedFilePath = s"$basePath/$uploadFilePath/$fileName"
		val convertedFilePath = s"$basePath/$convertFilePath/$fileName"

		if (Files.exists(Paths.get(convertedFilePath)))
			Right(Paths.get(convertedFilePath))
		else if (Files.exists(Paths.get(uploadedFilePath)))
			Right(Paths.get(uploadedFilePath))
		else Left(new FileNotFoundException())
	}
}

object FileHandler {
	def apply(config: ConfigFactory): FileHandler = FileHandler(config)
	def apply(): FileHandler = FileHandler(ConfigFactory.load())
}