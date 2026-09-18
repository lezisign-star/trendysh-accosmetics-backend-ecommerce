package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.service.CloudinarySignatureService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff-only — see {@link CloudinarySignatureService}'s javadoc for the whole upload flow. */
@RestController
@RequestMapping("/api/v1/admin/uploads")
public class UploadController {

    private final CloudinarySignatureService signatureService;

    public UploadController(CloudinarySignatureService signatureService) {
        this.signatureService = signatureService;
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @GetMapping("/cloudinary-signature")
    public CloudinarySignatureService.UploadSignature getSignature(
            @RequestParam(required = false, defaultValue = "trendysh/products") String folder) {
        return signatureService.sign(folder);
    }
}
