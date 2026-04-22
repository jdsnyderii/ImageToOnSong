package com.imagetoonsong.events;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class AppEventBus {
    private static final AppEventBus INSTANCE = new AppEventBus();
    private final List<Consumer<Object>> subscribers = new ArrayList<>();

    public static AppEventBus getInstance() { return INSTANCE; }

    public void subscribe(Consumer<Object> subscriber) {
        subscribers.add(subscriber);
    }

    public void post(Object event) {
        subscribers.forEach(s -> s.accept(event));
    }
}