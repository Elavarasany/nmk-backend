package nmk_backend.controller;

import jakarta.validation.Valid;
import nmk_backend.entity.WebsiteImage;
import nmk_backend.repository.WebsiteImageRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class WebsiteImageController {

    private static final Set<String> ALLOWED_IMAGE_KEYS = Set.of(
            "HERO", "LOGO", "STORE",
            "CATEGORY_EYEGLASSES",
            "CATEGORY_SUNGLASSES",
            "CATEGORY_CONTACT_LENSES",
            "CATEGORY_COMPUTER_GLASSES",
            "CATEGORY_POWER_SUNGLASSES",
            "CATEGORY_ACCESSORIES"
    );

    private final WebsiteImageRepository websiteImageRepository;

    public WebsiteImageController(WebsiteImageRepository websiteImageRepository) {
        this.websiteImageRepository = websiteImageRepository;
    }

    @GetMapping("/website-images")
    public List<WebsiteImage> getWebsiteImages() {
        return websiteImageRepository.findAll();
    }

    @GetMapping("/admin/website-images")
    public List<WebsiteImage> getAdminWebsiteImages() {
        return websiteImageRepository.findAll();
    }

    @PutMapping("/admin/website-images/{imageKey}")
    public ResponseEntity<?> saveWebsiteImage(
            @PathVariable String imageKey,
            @Valid @RequestBody WebsiteImage updatedImage) {

        String normalizedImageKey = imageKey == null
                ? ""
                : imageKey.trim()
                        .toUpperCase(Locale.ROOT)
                        .replaceAll("[^A-Z0-9_]", "");

        boolean isCategoryImageKey = normalizedImageKey.matches("^CATEGORY_[A-Z0-9_]+$");
        boolean isAllowedImageKey = ALLOWED_IMAGE_KEYS.contains(normalizedImageKey)
                || isCategoryImageKey;

        if (!isAllowedImageKey) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Unsupported website image key: " + imageKey,
                    "normalizedKey", normalizedImageKey
            ));
        }

        WebsiteImage image = websiteImageRepository
                .findByImageKey(normalizedImageKey)
                .orElseGet(WebsiteImage::new);

        image.setImageKey(normalizedImageKey);
        image.setTitle(updatedImage.getTitle());
        image.setImageUrl(updatedImage.getImageUrl());

        return ResponseEntity.ok(websiteImageRepository.save(image));
    }
}
