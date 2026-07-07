package jieqi.ai;

import jieqi.common.Color;
import jieqi.rules.BoardSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentInterfaceTest {

    @Test
    void agentSelectsFromPlayerViewAndTimeBudgetOnly() throws Exception {
        Method selectMove = Agent.class.getMethod("selectMove", PlayerView.class, TimeBudget.class);

        assertEquals(Optional.class, selectMove.getReturnType());
        assertThrows(
                NoSuchMethodException.class,
                () -> Agent.class.getMethod("selectMove", BoardSnapshot.class, Color.class),
                "Agent must not expose BoardSnapshot as its decision input");
    }
}
