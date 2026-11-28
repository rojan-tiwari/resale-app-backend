package com.resalemarket.messaging.dto;

/** Which side of a conversation the signed-in member is on. */
public enum ConversationRole {
    /** The member asked about somebody else's ad. */
    BUYER,
    /** Somebody asked about the member's ad. */
    SELLER
}
