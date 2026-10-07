package com.chinhbean.realtimechat.controller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
@RestController
public class FileUploadController {
    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);
    private final Path uploadDir;
    private final long quota;
    public FileUploadController(Environment environment) {
        uploadDir = Path.of(environment.getProperty("file.upload-dir", "uploads/images")).toAbsolutePath().normalize();
        quota = environment.getProperty("file.upload-quota-bytes", Long.class, 250L * 1024 * 1024);
    }
    @PostMapping("/upload") public synchronized String uploadFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an image.");
        if (file.getSize() > 10L * 1024 * 1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image must be under 10MB.");
        try {
            String extension;
            try (var input = file.getInputStream(); var stream = ImageIO.createImageInputStream(input)) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only JPEG, PNG or GIF images are supported.");
                var reader = readers.next();
                try {
                    reader.setInput(stream);
                    extension = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (extension.equals("jpeg")) extension = "jpg";
                    if (!Set.of("jpg", "png", "gif").contains(extension))
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported image format.");
                    if ((long) reader.getWidth(0) * reader.getHeight(0) > 20_000_000)
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image dimensions are too large.");
                    reader.read(0);
                } catch (IOException exception) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image data is corrupt.");
                } finally { reader.dispose(); }
            }
            Files.createDirectories(uploadDir);
            long used = 0;
            try (var stored = Files.list(uploadDir)) {
                for (var item : stored.filter(Files::isRegularFile).toList()) used += Files.size(item);
            }
            if (file.getSize() > quota - used)
                throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE, "Upload storage is full.");
            String filename = UUID.randomUUID() + "." + extension;
            Path destination = uploadDir.resolve(filename).normalize();
            if (!destination.startsWith(uploadDir)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename.");
            Files.write(destination, file.getBytes(), StandardOpenOption.CREATE_NEW);
            return "/images/" + filename;
        } catch (IOException exception) {
            logger.error("Image upload failed", exception);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save image.");
        }
    }
}
