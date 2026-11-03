package dev.p2pmc.net;

import java.net.InetSocketAddress;

/** A NAT hole that is open in both directions, with the reliable channel running over it. */
public record Link(ReliableChannel channel, InetSocketAddress remote) {
}
