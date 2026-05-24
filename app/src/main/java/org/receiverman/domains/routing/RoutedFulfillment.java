package org.receiverman.domains.routing;

import org.receiverman.domains.fulfillment.FulfillmentToken;

public record RoutedFulfillment(String routeKey, FulfillmentToken fulfillmentToken) {}
