package superfaktura.cli

import cats.effect.Sync
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.{ImageWriter, JpegWriter, PngWriter}
import scodec.bits.ByteVector
import superfaktura.{AttachmentFormat, ImagePrepAlgebra, PreparedAttachment, ReceiptBytes}

import scala.annotation.tailrec

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
      Sync[F].blocking(shrink(ImmutableImage.loader().fromBytes(attachment.value.toArray), writer, maxBytes))

  @tailrec
  private def shrink(image: ImmutableImage, writer: ImageWriter, maxBytes: Long): PreparedAttachment =
    val encoded = image.bytes(writer)
    if encoded.length <= maxBytes then PreparedAttachment.Fitted(ReceiptBytes(ByteVector(encoded)))
    else if image.width <= minDimension || image.height <= minDimension then
      PreparedAttachment.TooLarge(s"could not downscale below $maxBytes bytes")
    else shrink(image.scale(scaleStep), writer, maxBytes)
