package org.receiverman.domains;

public record RoutedFulfillment(String routeKey, FulfillmentToken fulfillmentToken) {}
