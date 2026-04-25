#import "ImageCropPlugin.h"

#import <Photos/Photos.h>
#import <MobileCoreServices/MobileCoreServices.h>
#import <ImageIO/ImageIO.h>
#import <math.h>

/// Pixel size after applying EXIF orientation (matches thumbnail with kCGImageSourceCreateThumbnailWithTransform).
static CGSize ImageCropOrientedPixelSize(CFDictionaryRef properties) {
    if (properties == NULL) {
        return CGSizeZero;
    }
    NSNumber *w = (__bridge NSNumber *)CFDictionaryGetValue(properties, kCGImagePropertyPixelWidth);
    NSNumber *h = (__bridge NSNumber *)CFDictionaryGetValue(properties, kCGImagePropertyPixelHeight);
    double dw = w ? w.doubleValue : 0;
    double dh = h ? h.doubleValue : 0;
    NSNumber *orientNum = (__bridge NSNumber *)CFDictionaryGetValue(properties, kCGImagePropertyOrientation);
    NSInteger orient = orientNum ? orientNum.integerValue : 1;
    // EXIF 5–8 swap width/height for upright display dimensions.
    if (orient >= 5 && orient <= 8) {
        return CGSizeMake(dh, dw);
    }
    return CGSizeMake(dw, dh);
}

/// Canonical path resolved under the app sandbox (mitigates path traversal via ../ or symlinks).
static NSString *_Nullable ImageCropValidatedSandboxFilePath(NSString *path, FlutterError *__autoreleasing *outError) {
    if (path.length == 0) {
        if (outError) {
            *outError = [FlutterError errorWithCode:@"INVALID_PATH"
                                           message:@"Path is empty"
                                           details:nil];
        }
        return nil;
    }
    if ([path rangeOfString:@"\0"].location != NSNotFound) {
        if (outError) {
            *outError = [FlutterError errorWithCode:@"INVALID_PATH"
                                           message:@"Path contains invalid characters"
                                           details:nil];
        }
        return nil;
    }
    NSString *resolved = [[path stringByResolvingSymlinksInPath] stringByStandardizingPath];
    NSString *home = [[NSHomeDirectory() stringByResolvingSymlinksInPath] stringByStandardizingPath];
    NSString *prefix = [home hasSuffix:@"/"] ? home : [home stringByAppendingString:@"/"];
    if (![resolved isEqualToString:home] && ![resolved hasPrefix:prefix]) {
        if (outError) {
            *outError = [FlutterError errorWithCode:@"INVALID_PATH"
                                           message:@"Path must be under the app sandbox"
                                           details:nil];
        }
        return nil;
    }
    return resolved;
}

@implementation ImageCropPlugin

+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar>*)registrar {
  FlutterMethodChannel* channel = [FlutterMethodChannel
                                   methodChannelWithName:@"plugins.lykhonis.com/image_crop"
                                   binaryMessenger:[registrar messenger]];
  ImageCropPlugin* instance = [ImageCropPlugin new];
  [registrar addMethodCallDelegate:instance channel:channel];
}

- (void)handleMethodCall:(FlutterMethodCall*)call result:(FlutterResult)result {
    if ([@"cropImage" isEqualToString:call.method]) {
        NSString* path = (NSString*)call.arguments[@"path"];
        FlutterError *pathError = nil;
        NSString *safePath = ImageCropValidatedSandboxFilePath(path, &pathError);
        if (!safePath) {
            result(pathError ?: [FlutterError errorWithCode:@"INVALID_PATH"
                                                    message:@"Invalid file path"
                                                    details:nil]);
            return;
        }
        NSNumber* left = (NSNumber*)call.arguments[@"left"];
        NSNumber* top = (NSNumber*)call.arguments[@"top"];
        NSNumber* right = (NSNumber*)call.arguments[@"right"];
        NSNumber* bottom = (NSNumber*)call.arguments[@"bottom"];
        NSNumber* scale = (NSNumber*)call.arguments[@"scale"];
        CGRect area = CGRectMake(left.floatValue, top.floatValue,
                                 right.floatValue - left.floatValue,
                                 bottom.floatValue - top.floatValue);
        [self cropImage:safePath area:area scale:scale result:result];
    } else if ([@"sampleImage" isEqualToString:call.method]) {
        NSString* path = (NSString*)call.arguments[@"path"];
        FlutterError *pathError = nil;
        NSString *safePath = ImageCropValidatedSandboxFilePath(path, &pathError);
        if (!safePath) {
            result(pathError ?: [FlutterError errorWithCode:@"INVALID_PATH"
                                                    message:@"Invalid file path"
                                                    details:nil]);
            return;
        }
        NSNumber* maximumWidth = (NSNumber*)call.arguments[@"maximumWidth"];
        NSNumber* maximumHeight = (NSNumber*)call.arguments[@"maximumHeight"];
        [self sampleImage:safePath
             maximumWidth:maximumWidth
            maximumHeight:maximumHeight
                   result:result];
    } else if ([@"getImageOptions" isEqualToString:call.method]) {
        NSString* path = (NSString*)call.arguments[@"path"];
        FlutterError *pathError = nil;
        NSString *safePath = ImageCropValidatedSandboxFilePath(path, &pathError);
        if (!safePath) {
            result(pathError ?: [FlutterError errorWithCode:@"INVALID_PATH"
                                                    message:@"Invalid file path"
                                                    details:nil]);
            return;
        }
        [self getImageOptions:safePath result:result];
    } else if ([@"requestPermissions" isEqualToString:call.method]){
        [self requestPermissionsWithResult:result];
    } else {
        result(FlutterMethodNotImplemented);
    }
}

- (void)cropImage:(NSString*)path
             area:(CGRect)area
            scale:(NSNumber*)scale
           result:(FlutterResult)result {
    [self execute:^{
        NSURL* url = [NSURL fileURLWithPath:path];
        CGImageSourceRef imageSource = CGImageSourceCreateWithURL((CFURLRef)url, NULL);

        if (imageSource == NULL) {
            result([FlutterError errorWithCode:@"INVALID"
                                       message:@"Image source cannot be opened"
                                       details:nil]);
            return;
        }

        CFDictionaryRef properties = CGImageSourceCopyPropertiesAtIndex(imageSource, 0, NULL);
        CGSize oriented = ImageCropOrientedPixelSize(properties);
        if (properties) {
            CFRelease(properties);
        }

        NSMutableDictionary* options = [NSMutableDictionary dictionaryWithDictionary:@{
            (id)kCGImageSourceCreateThumbnailWithTransform : @YES,
            (id)kCGImageSourceCreateThumbnailFromImageAlways : @YES,
        }];

        if (oriented.width >= 1.0 && oriented.height >= 1.0) {
            double lw = oriented.width;
            double lh = oriented.height;
            double targetW = lw * (double)area.size.width * scale.doubleValue;
            double targetH = lh * (double)area.size.height * scale.doubleValue;
            double maxPixel = MAX(MAX(targetW, targetH), 1.0);
            options[(id)kCGImageSourceThumbnailMaxPixelSize] = @(maxPixel);
        }

        CGImageRef image = CGImageSourceCreateThumbnailAtIndex(imageSource, 0, (__bridge CFDictionaryRef)options);

        if (image == NULL) {
            result([FlutterError errorWithCode:@"INVALID"
                                       message:@"Image cannot be opened"
                                       details:nil]);
            CFRelease(imageSource);
            return;
        }

        size_t width = CGImageGetWidth(image);
        size_t height = CGImageGetHeight(image);
        size_t scaledWidth = (size_t)MAX(1, lround(width * (double)area.size.width * scale.doubleValue));
        size_t scaledHeight = (size_t)MAX(1, lround(height * (double)area.size.height * scale.doubleValue));

        CGRect cropRect = CGRectMake((CGFloat)width * area.origin.x,
                                     (CGFloat)height * area.origin.y,
                                     (CGFloat)width * area.size.width,
                                     (CGFloat)height * area.size.height);
        cropRect = CGRectIntersection(cropRect, CGRectMake(0, 0, (CGFloat)width, (CGFloat)height));
        if (CGRectIsEmpty(cropRect) || cropRect.size.width < 1 || cropRect.size.height < 1) {
            CFRelease(image);
            CFRelease(imageSource);
            result([FlutterError errorWithCode:@"INVALID"
                                       message:@"Invalid crop region"
                                       details:nil]);
            return;
        }

        CGImageRef croppedImage = CGImageCreateWithImageInRect(image, cropRect);
        CFRelease(image);
        CFRelease(imageSource);

        if (croppedImage == NULL) {
            result([FlutterError errorWithCode:@"INVALID"
                                       message:@"Image cannot be cropped"
                                       details:nil]);
            return;
        }

        if (fabs(scale.doubleValue - 1.0) > 1e-6) {
            size_t bitsPerComponent = CGImageGetBitsPerComponent(croppedImage);
            CGImageAlphaInfo bitmapInfo = CGImageGetAlphaInfo(croppedImage);
            CGColorSpaceRef colorspace = CGImageGetColorSpace(croppedImage);
            BOOL createdColorSpace = NO;
            if (colorspace == NULL) {
                colorspace = CGColorSpaceCreateDeviceRGB();
                createdColorSpace = YES;
            }

            CGContextRef context = CGBitmapContextCreate(NULL,
                                                          scaledWidth,
                                                          scaledHeight,
                                                          bitsPerComponent,
                                                          0,
                                                          colorspace,
                                                          bitmapInfo);

            if (createdColorSpace) {
                CGColorSpaceRelease(colorspace);
            }

            if (context == NULL) {
                result([FlutterError errorWithCode:@"INVALID"
                                           message:@"Image cannot be scaled"
                                           details:nil]);
                CFRelease(croppedImage);
                return;
            }

            CGContextSetInterpolationQuality(context, kCGInterpolationHigh);
            CGRect dstRect = CGRectMake(0, 0, (CGFloat)scaledWidth, (CGFloat)scaledHeight);
            CGContextDrawImage(context, dstRect, croppedImage);

            CGImageRef scaledImage = CGBitmapContextCreateImage(context);
            CGContextRelease(context);
            CFRelease(croppedImage);
            croppedImage = scaledImage;

            if (croppedImage == NULL) {
                result([FlutterError errorWithCode:@"INVALID"
                                           message:@"Image cannot be scaled"
                                           details:nil]);
                return;
            }
        }

        NSURL* croppedUrl = [self createTemporaryImageUrl];
        bool saved = [self saveImage:croppedImage url:croppedUrl];
        CFRelease(croppedImage);

        if (saved) {
            result(croppedUrl.path);
        } else {
            result([FlutterError errorWithCode:@"INVALID"
                                       message:@"Cropped image cannot be saved"
                                       details:nil]);
        }
    }];
}

- (void)sampleImage:(NSString*)path
       maximumWidth:(NSNumber*)maximumWidth
      maximumHeight:(NSNumber*)maximumHeight
             result:(FlutterResult)result {
    [self execute:^{
        NSURL* url = [NSURL fileURLWithPath:path];
        CGImageSourceRef image = CGImageSourceCreateWithURL((CFURLRef) url, NULL);
        
        if (image == NULL) {
            result([FlutterError errorWithCode:@"INVALID"
                                       message:@"Image source cannot be opened"
                                       details:nil]);
            return;
        }

        CFDictionaryRef properties = CGImageSourceCopyPropertiesAtIndex(image, 0, nil);

        if (properties == NULL) {
            CFRelease(image);
            result([FlutterError errorWithCode:@"INVALID"
                                       message:@"Image source properties cannot be copied"
                                       details:nil]);
            return;
        }

        NSNumber* width = (NSNumber*) CFDictionaryGetValue(properties, kCGImagePropertyPixelWidth);
        NSNumber* height = (NSNumber*) CFDictionaryGetValue(properties, kCGImagePropertyPixelHeight);
        CFRelease(properties);

        double widthRatio = MIN(1.0, maximumWidth.doubleValue / width.doubleValue);
        double heightRatio = MIN(1.0, maximumHeight.doubleValue / height.doubleValue);
        double ratio = MAX(widthRatio, heightRatio);
        NSNumber* maximumSize = @(MAX(width.doubleValue * ratio, height.doubleValue * ratio));

        CFDictionaryRef options = (__bridge CFDictionaryRef) @{
                                                               (id) kCGImageSourceCreateThumbnailWithTransform: @YES,
                                                               (id) kCGImageSourceCreateThumbnailFromImageAlways : @YES,
                                                               (id) kCGImageSourceThumbnailMaxPixelSize : maximumSize
                                                               };
        CGImageRef sampleImage = CGImageSourceCreateThumbnailAtIndex(image, 0, options);
        CFRelease(image);

        if (sampleImage == NULL) {
            result([FlutterError errorWithCode:@"INVALID"
                                       message:@"Image sample cannot be created"
                                       details:nil]);
            return;
        }

        NSURL* sampleUrl = [self createTemporaryImageUrl];
        bool saved = [self saveImage:sampleImage url:sampleUrl];
        CFRelease(sampleImage);
        
        if (saved) {
            result(sampleUrl.path);
        } else {
            result([FlutterError errorWithCode:@"INVALID"
                                       message:@"Image sample cannot be saved"
                                       details:nil]);
        }
    }];
}

- (void)getImageOptions:(NSString*)path result:(FlutterResult)result {
    [self execute:^{
        NSURL* url = [NSURL fileURLWithPath:path];
        CGImageSourceRef image = CGImageSourceCreateWithURL((CFURLRef) url, NULL);
        
        if (image == NULL) {
            result([FlutterError errorWithCode:@"INVALID"
                                       message:@"Image source cannot be opened"
                                       details:nil]);
            return;
        }

        CFDictionaryRef properties = CGImageSourceCopyPropertiesAtIndex(image, 0, nil);
        CFRelease(image);
        
        if (properties == NULL) {
            result([FlutterError errorWithCode:@"INVALID"
                                       message:@"Image source properties cannot be copied"
                                       details:nil]);
            return;
        }

        NSNumber* width = (NSNumber*) CFDictionaryGetValue(properties, kCGImagePropertyPixelWidth);
        NSNumber* height = (NSNumber*) CFDictionaryGetValue(properties, kCGImagePropertyPixelHeight);
        CFRelease(properties);

        result(@{ @"width": width,  @"height": height });
    }];
}

- (void)requestPermissionsWithResult:(FlutterResult)result {
    [PHPhotoLibrary requestAuthorization:^(PHAuthorizationStatus status) {
        BOOL granted = (status == PHAuthorizationStatusAuthorized);
        if (@available(iOS 14, *)) {
            granted = granted || (status == PHAuthorizationStatusLimited);
        }
        result(@(granted));
    }];
}

- (bool)saveImage:(CGImageRef)image url:(NSURL*)url {
    CGImageDestinationRef destination = CGImageDestinationCreateWithURL((CFURLRef) url, kUTTypeJPEG, 1, NULL);
    if (destination == NULL) {
        return false;
    }
    CGImageDestinationAddImage(destination, image, NULL);
    bool finalized = CGImageDestinationFinalize(destination);
    CFRelease(destination);
    return finalized;
}

- (NSURL*)createTemporaryImageUrl {
    NSString* temproraryDirectory = NSTemporaryDirectory();
    NSString* guid = [[NSProcessInfo processInfo] globallyUniqueString];
    NSString* sampleName = [[@"image_crop_" stringByAppendingString:guid] stringByAppendingString:@".jpg"];
    NSString* samplePath = [temproraryDirectory stringByAppendingPathComponent:sampleName];
    return [NSURL fileURLWithPath:samplePath];
}

- (void)execute:(void (^)(void))block {
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), block);
}

@end
