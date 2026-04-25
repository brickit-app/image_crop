part of image_crop;

class ImageOptions {
  final int width;
  final int height;

  ImageOptions({
    required this.width,
    required this.height,
  });

  @override
  int get hashCode => Object.hash(width, height);

  @override
  bool operator ==(other) =>
      other is ImageOptions && other.width == width && other.height == height;

  @override
  String toString() => '$runtimeType(width: $width, height: $height)';
}

class ImageCrop {
  static const MethodChannel _channel = MethodChannel('plugins.lykhonis.com/image_crop');

  static Future<bool> requestPermissions() async {
    final Object? result = await _channel.invokeMethod<Object?>('requestPermissions');
    if (result is bool) {
      return result;
    }
    throw StateError('Unexpected requestPermissions result: $result');
  }

  static Future<ImageOptions> getImageOptions({
    required File file,
  }) async {
    final Object? raw;
    try {
      raw = await _channel.invokeMethod<Object?>('getImageOptions', {'path': file.path});
    } on PlatformException catch (e) {
      if (e.code == 'INVALID_PATH') {
        throw ImageCropInvalidPathException(e.message ?? 'Invalid file path');
      }
      rethrow;
    }
    if (raw is! Map) {
      throw StateError('Unexpected getImageOptions result: $raw');
    }
    final width = raw['width'];
    final height = raw['height'];
    if (width is! num || height is! num) {
      throw StateError('Invalid getImageOptions dimensions: $raw');
    }
    return ImageOptions(width: width.toInt(), height: height.toInt());
  }

  static Future<File> cropImage({
    required File file,
    required Rect area,
    double? scale,
  }) async {
    final Object? path;
    try {
      path = await _channel.invokeMethod<Object?>('cropImage', {
        'path': file.path,
        'left': area.left,
        'top': area.top,
        'right': area.right,
        'bottom': area.bottom,
        'scale': scale ?? 1.0,
      });
    } on PlatformException catch (e) {
      if (e.code == 'INVALID_PATH') {
        throw ImageCropInvalidPathException(e.message ?? 'Invalid file path');
      }
      rethrow;
    }
    if (path is! String) {
      throw StateError('Unexpected cropImage result: $path');
    }
    return File(path);
  }

  static Future<File> sampleImage({
    required File file,
    int? preferredSize,
    int? preferredWidth,
    int? preferredHeight,
  }) async {
    if (preferredSize == null && (preferredWidth == null || preferredHeight == null)) {
      throw ArgumentError(
        'Preferred size or both width and height of a resampled image must be specified.',
      );
    }

    final Object? path;
    try {
      path = await _channel.invokeMethod<Object?>('sampleImage', {
        'path': file.path,
        'maximumWidth': preferredSize ?? preferredWidth,
        'maximumHeight': preferredSize ?? preferredHeight,
      });
    } on PlatformException catch (e) {
      if (e.code == 'INVALID_PATH') {
        throw ImageCropInvalidPathException(e.message ?? 'Invalid file path');
      }
      rethrow;
    }
    if (path is! String) {
      throw StateError('Unexpected sampleImage result: $path');
    }
    return File(path);
  }
}
