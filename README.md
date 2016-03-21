# photo-organizer

Organize photos using EXIF DateTimeOriginal (fall back to file create time), reverse geocoded GPS location placename,
and optional DocumentName EXIF tag.

```shell
java -jar photo-organizer.jar /source/directory ./target/directory
```

## Compiling

Uses [OfflineReverseGeocode](https://github.com/AReallyGoodName/OfflineReverseGeocode) which isn't on Maven Central,
so that must be checked out and built locally. Note that the version in the 1.0 tag needs to be fixed before building.

Download a placenames zip from http://download.geonames.org/export/dump/ (`cities1000.zip recommended`)
and copy it to `src/main/resources/placenames.zip` before building.

Run `mvn clean install` to compile to `target/photo-organizer.jar`.

## exiftool

exiftool is useful for finding bad EXIF data and fixing it up. Some examples:

Check directory for photos in the wrong year
```shell
exiftool -r -DateTimeOriginal /path/to/dir | grep -B 1 2004
```

Conditional adjustment
```shell
exiftool -r '-DateTimeOriginal+=2:10:0 0:0:0' -if '$DateTimeOriginal lt "2006:02"' /path/to/files
```

Adjust date time original date with offsets
```shell
exiftool -r "-DateTimeOriginal+=1:7:26 0:0:0" /path/to/dir
```

Set absolute DateTimeOriginal for photos without it
```shell
exiftool -r -m "-DateTimeOriginal=2007:01:01 00:00:00" -if '$FileType eq "JPEG"' -if 'not $DateTimeOriginal' /path/to/dir
```

Set DocumentName on photos (used in filename generation):
```shell
exiftool -r "-DocumentName=Some album" -if '$FileType eq "JPEG"' /path/to/files
```

Delete exiftool backups after reviewing
```shell
exiftool -delete_original /path/to/dir
```
