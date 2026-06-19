package superfaktura

object Attachment:
  // SuperFaktura caps a single attachment at 4 MB; larger raster images are downscaled to fit, others flagged.
  val maxBytes: Long = 4L * 1024 * 1024
