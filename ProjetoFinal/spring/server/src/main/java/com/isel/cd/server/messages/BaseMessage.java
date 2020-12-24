package com.isel.cd.server.messages;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
public class BaseMessage implements Serializable {
    private TYPE type;

    public BaseMessage(TYPE type) {
        this.type = type;
    }

    public enum TYPE{
        NEW_LEADER,
        WHO_IS_LEADER,
        NEW_DATA_FROM_LEADER,
        ASK_DATA_TO_LEADER,
        RESPONSE_DATA_FROM_LEADER,
        WRITE_DATA_TO_LEADER;
    }

}
