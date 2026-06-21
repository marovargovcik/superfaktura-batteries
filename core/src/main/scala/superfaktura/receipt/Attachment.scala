package superfaktura.receipt

object Attachment:
  // SuperFaktura caps a single attachment at 4 MB; we use the decimal megabyte so we never exceed the
  // limit whether it is enforced as 4,000,000 or 4,194,304 bytes. Larger rasters are downscaled, others flagged.
  val maxBytes: Long = 4_000_000
