package superfaktura.cli.receipt

import scala.annotation.tailrec

import cats.effect.Sync
import cats.syntax.all.*
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.{ImageWriter, JpegWriter, PngWriter}
import scodec.bits.ByteVector
import superfaktura.CliError
import superfaktura.receipt.{AttachmentFormat, ImagePrepAlgebra, PreparedAttachment, ReceiptBytes}

object ScrimageImagePrep:

  private val minDimension = 16
  private val scaleStep = 0.7

  def fitting[F[_]: Sync](maxBytes: Long): ImagePrepAlgebra[F] = new ImagePrepAlgebra[F]:
    override def fit(attachment: ReceiptBytes, format: AttachmentFormat): F[PreparedAttachment] =
      if attachment.value.size <= maxBytes then Sync[F].pure(PreparedAttachment.Fitted(attachment))
      else
        format match
          case AttachmentFormat.Jpeg => downscale(attachment, JpegWriter())
          case AttachmentFormat.Png => downscale(attachment, PngWriter())
          case AttachmentFormat.Pdf | AttachmentFormat.Heic =>
            Sync[F].pure(PreparedAttachment.TooLarge(s"$format over $maxBytes bytes cannot be downscaled"))

    private def downscale(attachment: ReceiptBytes, writer: ImageWriter): F[PreparedAttachment] =
      Sync[F]
        .blocking(shrink(ImmutableImage.loader().fromBytes(attachment.value.toArray), writer, maxBytes))
        .adaptError { case error: Exception => CliError.ImageInvalid(error.getMessage) }

  @tailrec
  private def shrink(image: ImmutableImage, writer: ImageWriter, maxBytes: Long): PreparedAttachment =
    val encoded = image.bytes(writer)
    if encoded.length <= maxBytes then PreparedAttachment.Fitted(ReceiptBytes(ByteVector(encoded)))
    else if image.width <= minDimension || image.height <= minDimension then
      PreparedAttachment.TooLarge(s"still over $maxBytes bytes after downscaling")
    else shrink(image.scale(scaleStep), writer, maxBytes)
