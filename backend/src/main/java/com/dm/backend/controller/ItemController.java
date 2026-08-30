package com.dm.backend.controller;

import com.dm.backend.entity.Item;
import com.dm.backend.repository.ItemRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 数据库集成演示接口（Item CRUD）。
 */
@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemRepository itemRepository;

    public ItemController(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    @GetMapping
    public List<Item> list() {
        return itemRepository.findAll();
    }

    @PostMapping
    public Item create(@RequestBody Map<String, String> body) {
        Item item = new Item(body.getOrDefault("name", "unnamed"));
        item.setCreatedAt(LocalDateTime.now());
        return itemRepository.save(item);
    }
}
