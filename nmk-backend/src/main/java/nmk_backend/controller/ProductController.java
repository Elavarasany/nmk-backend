package nmk_backend.controller;

import nmk_backend.dto.CustomerProductCatalogResponse;
import nmk_backend.entity.Product;
import nmk_backend.service.CustomerProductCatalogService;
import nmk_backend.service.ProductService;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@CrossOrigin(origins = "http://localhost:5173")
public class ProductController {

    private final ProductService productService;
    private final CustomerProductCatalogService customerProductCatalogService;

    public ProductController(
            ProductService productService,
            CustomerProductCatalogService customerProductCatalogService
    ) {
        this.productService = productService;
        this.customerProductCatalogService = customerProductCatalogService;
    }

    @GetMapping
    public List<CustomerProductCatalogResponse.ProductItem> getAllProducts() {
        return customerProductCatalogService.getActiveProducts();
    }

    @PostMapping
    public Product addProduct(@RequestBody Product product) {
        return productService.addProduct(product);
    }

    @PutMapping("/{id}")
    public Product updateProduct(
            @PathVariable Long id,
            @RequestBody Product product) {
        return productService.updateProduct(id, product);
    }

    @GetMapping("/{id}")
    public CustomerProductCatalogResponse.ProductItem getProductById(@PathVariable Long id) {
        return customerProductCatalogService.getActiveProductById(id);
    }
}
