package com.github.srstclair.photoorganizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import java.util.zip.ZipInputStream;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.random.EmpiricalDistribution;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.apache.log4j.Logger;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.neovisionaries.i18n.CountryCode;

import geocode.GeoName;
import geocode.ReverseGeoCode;

public class PhotoOrganizer {
    private static final Logger LOGGER = Logger.getLogger(PhotoOrganizer.class);
    private static final DateFormat ISO_8601_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HHmmssSSSz");
    private static final DateFormat SHORT_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
    private static final Date CAMERA_INVENTED = new Date(-8993635200000L);
    private static final File DEFAULT_SORT_DIR = new File("./sorted");
    private static final int HISTOGRAM_BINS = 20;
    private static final int HISTOGRAM_WIDTH = 100;

    public static final ZipInputStream PLACENAMES = new ZipInputStream(
            ReverseGeoCode.class.getResourceAsStream("/placenames.zip"));

    private static ReverseGeoCode GEO_CODER;

    private static ReverseGeoCode getGeoCoder() throws IOException {
        if (GEO_CODER == null) {
            synchronized (PhotoOrganizer.class) {
                GEO_CODER = new ReverseGeoCode(PLACENAMES, true);
            }
        }
        return GEO_CODER;
    }

    
    public static void main(String[] args) throws IOException, ImageProcessingException, MetadataException, NoSuchAlgorithmException {
        if (args.length < 1) {
            LOGGER.error("Source photo directory must be passed as first argument");
            System.exit(1);
        }        
        File sourceDir = new File(args[0]);
        if (!sourceDir.exists()) {
            LOGGER.error("Source photo directory doesn't exist");
            System.exit(1);
        }

        File sortDir = DEFAULT_SORT_DIR;
        if (args.length >=2) {
            sortDir = new File(args[1]);
        }
        checkDir(sortDir);
        File unhandledDir = new File(sortDir, "other");

        int photosProcessed = 0;
        int unhandledFiles = 0;
        int alreadyProcessed = 0;
        List<Date> dates = Lists.newArrayList();

        Collection<File> files = FileUtils.listFiles(sourceDir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);
        for (File file : files) {
            String relativePath = getRelativePath(sourceDir, file);

            //check for non-jpeg extensions, flatten and move to unhandled dir
            String ext = FilenameUtils.getExtension(file.getName());
            if (!ext.equalsIgnoreCase("jpg") && !ext.equalsIgnoreCase("jpeg")) {
                LOGGER.debug("Unhandled file " + file.getName());
                File unhandledTargetFile = new File(unhandledDir,
                        relativePath.replaceAll(File.separator, "_"));
                checkDir(unhandledTargetFile.getParentFile());
                
                //check to see if the file has already been processed
                if (unhandledTargetFile.exists()) {
                    if (getMD5(file).equals(getMD5(unhandledTargetFile))) {
                        //already processed this file
                        alreadyProcessed++;
                    } else {
                        LOGGER.error("Target for " + relativePath + " already exists at " + unhandledTargetFile.getAbsolutePath()
                            + " but it's not the same file, aborting...");
                        System.exit(1);
                    }
                } else {
                    FileUtils.copyFile(file, unhandledTargetFile);
                    unhandledFiles++;                    
                }
                continue;                
            }

            //file is a jpeg
            File photo = file;
            StringBuffer filenameBuffer = new StringBuffer();

            Metadata metadata = ImageMetadataReader.readMetadata(photo);
            if (metadata == null) {
                continue;
            }

            Date dateTimeOriginal = getDateTimeOriginal(metadata);
            if (dateTimeOriginal == null) {
                dateTimeOriginal = getFileCreationDate(photo);
                if (dateTimeOriginal != null) {
                    LOGGER.debug("No date/time for " + photo + ", falling back to file creation time "
                            + ISO_8601_DATE_FORMAT.format(dateTimeOriginal));
                }
            }

            if (dateTimeOriginal == null) {
                String err = "No date/time for photo " + relativePath + " after trying Date/time"
                        + " and file creation time";
                LOGGER.error(err);
                System.exit(1);
            }

            Calendar cal = Calendar.getInstance();
            cal.setTime(dateTimeOriginal);

            if (cal.get(Calendar.YEAR) == 2) {
                LOGGER.warn(photo);
            }
            String documentName = getDocumentName(metadata);
            GeoLocation location = getLocation(metadata);
            GeoName placename = null;
            if (location != null) {
                placename = getPlacename(location);
            }

            filenameBuffer.append(ISO_8601_DATE_FORMAT.format(dateTimeOriginal));

            //add placename if found
            if (placename != null) {
                filenameBuffer.append("_" + underscore(placename.name));
                if (!placename.country.equalsIgnoreCase("US")) {
                    String country = getCountry(placename);
                    if (country != null) {
                        filenameBuffer.append("_" + underscore(country));
                    } else {
                        filenameBuffer.append("_" + placename.country);
                    }
                }
            }

            if (documentName != null && !documentName.trim().isEmpty()) {
                filenameBuffer.append("_" + documentName.replaceAll(" ", "_"));
            }

            String filename = filenameBuffer.toString() + ".jpg";

            File targetDir = new File(sortDir, Integer.toString(cal.get(Calendar.YEAR)));

            if (!targetDir.exists()) {
                if (!targetDir.mkdirs()) {
                    throw new IllegalStateException("Couldn't create target directory " + targetDir);
                }
            }
            File targetFile = new File(targetDir, filename);

            if (targetFile.exists()) {
                String photoMD5 = getMD5(photo);
                String targetMD5 = getMD5(targetFile);
                if (photoMD5.equals(targetMD5)) {
                    LOGGER.debug("Photo " + photo.getName() + " already copied to " + targetFile);
                    alreadyProcessed++;
                    continue;
                } else {
                    LOGGER.debug("Target file " + targetFile + " already exists for photo " + relativePath + 
                            ", adding md5 hash");
                    filename = filenameBuffer.toString() + "_" + photoMD5 + ".jpg";
                    targetFile = new File(targetDir, filename);
                    Files.copy(photo, targetFile);
                }
            } else {
                Files.copy(photo, targetFile);
                LOGGER.debug(photo.getName() + " -> " + targetFile);
            }

            //update trackers
            photosProcessed++;
            dates.add(dateTimeOriginal);
        }

        //finished        
        LOGGER.info("Processed " + photosProcessed + " photos");
        if (unhandledFiles > 0) {
            LOGGER.info("Moved " + unhandledFiles + " unhandled " + getFilesLabel(unhandledFiles) 
                    + " to " + unhandledDir.getName());
        }
        if (alreadyProcessed > 0) {
            LOGGER.info("Found " + alreadyProcessed + " previously processed "
                    + getFilesLabel(alreadyProcessed));
        }
        if (!dates.isEmpty()) {
            Collections.sort(dates);
            LOGGER.info("Date range: " + SHORT_DATE_FORMAT.format(dates.get(0)) + " - "
                    + SHORT_DATE_FORMAT.format(dates.get(dates.size() - 1)));
            showHistogram(dates);
        }
    }

    private static String getDocumentName(Metadata metadata) {
        Objects.requireNonNull(metadata);
        Collection<ExifIFD0Directory> exifDirs = metadata.getDirectoriesOfType(ExifIFD0Directory.class);
        if (exifDirs == null || exifDirs.isEmpty()) {
            return null;
        }
        for (ExifIFD0Directory exifDir : exifDirs) {
            String documentName = exifDir.getString(ExifIFD0Directory.TAG_DOCUMENT_NAME);
            if (documentName != null) {
                return documentName.trim();
            }
        }
        return null;
    }        
    
    private static Date getDateTimeOriginal(Metadata metadata) {
        Objects.requireNonNull(metadata);
        Collection<ExifSubIFDDirectory> exifDirs = metadata.getDirectoriesOfType(ExifSubIFDDirectory.class);
        if (exifDirs == null || exifDirs.isEmpty()) {
            return null;
        }
        for (ExifSubIFDDirectory exifDir : exifDirs) {
            Date dateTimeOriginal = exifDir.getDate(ExifIFD0Directory.TAG_DATETIME_ORIGINAL,
                    TimeZone.getDefault());
            if (dateTimeOriginal != null && dateTimeOriginal.after(CAMERA_INVENTED)) {
                return dateTimeOriginal;
            }
        }
        return null;
    }

    private static String getMD5(File file) throws IOException {
        Objects.requireNonNull(file);
        FileInputStream photoStream = new FileInputStream(file);
        String md5 = DigestUtils.md5Hex(photoStream);
        photoStream.close();
        return md5;
    }

    private static GeoLocation getLocation(Metadata metadata) {
        Objects.requireNonNull(metadata);
        Collection<GpsDirectory> gpsDirs = metadata.getDirectoriesOfType(GpsDirectory.class);
        if (gpsDirs == null || gpsDirs.isEmpty()) {
            return null;
        }
        for (GpsDirectory gpsDir : gpsDirs) {
            GeoLocation geoLocation = gpsDir.getGeoLocation();
            if (geoLocation != null) {
                return geoLocation;
            }
        }
        return null;        
    }

    private static GeoName getPlacename(GeoLocation location) throws IOException {
        Objects.requireNonNull(location);
        return getGeoCoder().nearestPlace(location.getLatitude(), location.getLongitude());
        
    }

    private static String getCountry(GeoName placename) {
        Objects.requireNonNull(placename);
        return CountryCode.getByCode(placename.country).getName();
    }

    private static String getRelativePath(File root, File file) {
        return root.toPath().relativize(file.toPath()).toString();     
    }

    private static void checkDir(File dir) {
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                throw new IllegalStateException("Couldn't create directory " + dir);
            }
        } else if (!dir.isDirectory()) {
            throw new IllegalStateException(dir + " exists but isn't a directory");
        }        
    }

    private static String underscore(String str) {
        if (str == null ) {
            return null;
        }
        return str.replaceAll("\\s", "_");
    }

    private static Date getFileCreationDate(File file) throws IOException {
        BasicFileAttributes attr = java.nio.file.Files.readAttributes(file.toPath(), BasicFileAttributes.class);
        if (attr == null || attr.creationTime() == null) {
            return null;
        } 
        return new Date(attr.creationTime().toMillis());
    }

    private static void showHistogram(List<Date> dates) {
        double[] data = new double[dates.size()];
        for (int i = 0; i < dates.size(); i++) {
            data[i] = dates.get(i).getTime();
        }

        EmpiricalDistribution distribution = new EmpiricalDistribution(HISTOGRAM_BINS);
        distribution.load(data);

        double starsPerPhoto = (double) HISTOGRAM_WIDTH / dates.size() ;
        for(SummaryStatistics stats: distribution.getBinStats()) {
            double min = stats.getMin();
            double max = stats.getMax();
            if (!Double.isNaN(min) && !Double.isNaN(max)) {
                Date minDate = new Date((long) min);
                Date maxDate = new Date((long) max);
                String minDateStr = SHORT_DATE_FORMAT.format(minDate);
                String maxDateStr = SHORT_DATE_FORMAT.format(maxDate);
                LOGGER.info(minDateStr + " - " + maxDateStr + ": "
                        + StringUtils.repeat('*', (int) Math.ceil(starsPerPhoto * stats.getN())));
            }
        }
    }

    private static String getFilesLabel(int files) {
        return files == 1 ? "file" : "files";
    }

    @SuppressWarnings("unused")
    private static void showAllExif(Metadata metadata) {
        Objects.requireNonNull(metadata);
        for (Directory directory : metadata.getDirectories()) {
            LOGGER.info(directory.getClass());
            for (Tag tag : directory.getTags()) {
                LOGGER.info("[" + directory.getName() + "] - " + tag.getTagName()
                + " = " + tag.getDescription());
            }
            if (directory.hasErrors()) {
                for (String error : directory.getErrors()) {
                    LOGGER.error("ERROR: " + error);
                }
            }
        }
    }
}

    