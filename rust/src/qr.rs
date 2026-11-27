//! QR image generation for pairing links.

use std::io;
use std::path::Path;

use image::{GrayImage, Luma};
use qrcode::QrCode;

/// Render `payload` as a grayscale image. The module size and quiet zone match
/// the images printed by the old Python server (`box_size=8`, `border=2`).
pub fn render(payload: &str) -> io::Result<GrayImage> {
    let code = QrCode::new(payload)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err.to_string()))?;
    let bare = code
        .render::<Luma<u8>>()
        .quiet_zone(false)
        .module_dimensions(8, 8)
        .build();
    let (width, height) = bare.dimensions();
    let mut image = GrayImage::from_pixel(width + 32, height + 32, Luma([255]));
    image::imageops::overlay(&mut image, &bare, 16, 16);
    Ok(image)
}

/// Save a scannable PNG for `payload`.
pub fn save_png(payload: &str, path: &Path) -> io::Result<()> {
    render(payload)?.save(path).map_err(|err| io::Error::other(err.to_string()))
}

/// Render a payload as a terminal-friendly Unicode QR, used for console output.
#[allow(dead_code)]
pub fn terminal(payload: &str) -> io::Result<String> {
    use qrcode::render::unicode::Dense1x2;

    let code = QrCode::new(payload)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err.to_string()))?;
    Ok(format!(
        "{}",
        code.render::<Dense1x2>().quiet_zone(true).build()
    ))
}
