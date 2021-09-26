/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.wiring;

import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.fpga.designrulecheck.Netlist;
import com.cburch.logisim.fpga.designrulecheck.NetlistComponent;
import com.cburch.logisim.fpga.hdlgenerator.AbstractHDLGeneratorFactory;
import com.cburch.logisim.fpga.hdlgenerator.HDL;
import com.cburch.logisim.fpga.hdlgenerator.HDLGeneratorFactory;
import com.cburch.logisim.fpga.hdlgenerator.HDLParameters;
import com.cburch.logisim.fpga.hdlgenerator.TickComponentHDLGeneratorFactory;
import com.cburch.logisim.instance.Port;
import com.cburch.logisim.util.LineBuffer;
import java.util.ArrayList;
import java.util.SortedMap;
import java.util.TreeMap;

public class ClockHDLGeneratorFactory extends AbstractHDLGeneratorFactory {

  public static final int NR_OF_CLOCK_BITS = 5;
  public static final int DERIVED_CLOCK_INDEX = 0;
  public static final int INVERTED_DERIVED_CLOCK_INDEX = 1;
  public static final int POSITIVE_EDGE_TICK_INDEX = 2;
  public static final int NEGATIVE_EDGE_TICK_INDEX = 3;
  public static final int GLOBAL_CLOCK_INDEX = 4;
  private static final String HIGH_TICK_STR = "HighTicks";
  private static final int HIGH_TICK_ID = -1;
  private static final String LOW_TICK_STR = "LowTicks";
  private static final int LOW_TICK_ID = -2;
  private static final String PHASE_STR = "Phase";
  private static final int PHASE_ID = -3;
  private static final String NR_OF_BITS_STR = "NrOfBits";
  private static final int NR_OF_BITS_ID = -4;

  public ClockHDLGeneratorFactory() {
    super("base");
    myParametersList
        .add(HIGH_TICK_STR, HIGH_TICK_ID, HDLParameters.MAP_INT_ATTRIBUTE, Clock.ATTR_HIGH)
        .add(LOW_TICK_STR, LOW_TICK_ID, HDLParameters.MAP_INT_ATTRIBUTE, Clock.ATTR_LOW)
        .add(PHASE_STR, PHASE_ID, HDLParameters.MAP_INT_ATTRIBUTE, Clock.ATTR_PHASE, 1)
        .add(NR_OF_BITS_STR, NR_OF_BITS_ID, HDLParameters.MAP_LN2, Clock.ATTR_HIGH, Clock.ATTR_LOW);
    myWires
        .addWire("s_counter_next", NR_OF_BITS_ID)
        .addWire("s_counter_is_zero", 1)
        .addRegister("s_output_regs", NR_OF_CLOCK_BITS - 1)
        .addRegister("s_buf_regs", 2)
        .addRegister("s_counter_reg", NR_OF_BITS_ID)
        .addRegister("s_derived_clock_reg", PHASE_ID);
    myPorts
        .add(Port.INPUT, "GlobalClock", 1, 0)
        .add(Port.INPUT, "ClockTick", 1, 1)
        .add(Port.OUTPUT, "ClockBus", NR_OF_CLOCK_BITS, 2);
  }

  @Override
  public SortedMap<String, String> getPortMap(Netlist Nets, Object MapInfo) {
    final var map = new TreeMap<String, String>();
    if (!(MapInfo instanceof NetlistComponent)) return map;
    final var componentInfo = (NetlistComponent) MapInfo;
    map.put("GlobalClock", TickComponentHDLGeneratorFactory.FPGA_CLOCK);
    map.put("ClockTick", TickComponentHDLGeneratorFactory.FPGA_TICK);
    map.put("ClockBus", "s_" + GetClockNetName(componentInfo.getComponent(), Nets));
    return map;
  }

  private String GetClockNetName(Component comp, Netlist TheNets) {
    StringBuilder Contents = new StringBuilder();
    int ClockNetId = TheNets.getClockSourceId(comp);
    if (ClockNetId >= 0) {
      Contents.append(HDLGeneratorFactory.CLOCK_TREE_NAME).append(ClockNetId);
    }
    return Contents.toString();
  }

  @Override
  public ArrayList<String> getModuleFunctionality(Netlist TheNetlist, AttributeSet attrs) {
    final var Contents = LineBuffer.getHdlBuffer()
            .pair("phase", PHASE_STR)
            .pair("nrOfBits", NR_OF_BITS_STR)
            .pair("lowTick", LOW_TICK_STR)
            .pair("highTick", HIGH_TICK_STR)
            .addRemarkBlock("Here the output signals are defines; we synchronize them all on the main clock");

    if (HDL.isVHDL()) {
      Contents.add("""
          ClockBus <= GlobalClock&s_output_regs;
          makeOutputs : PROCESS( GlobalClock )
          BEGIN
             IF (GlobalClock'event AND (GlobalClock = '1')) THEN
                s_buf_regs(0)     <= s_derived_clock_reg({{phase}} - 1);
                s_buf_regs(1)     <= NOT(s_derived_clock_reg({{phase}} - 1));
                s_output_regs(0)  <= s_buf_regs(0);
                s_output_regs(1)  <= s_buf_regs(1);
                s_output_regs(2)  <= NOT(s_buf_regs(0)) AND s_derived_clock_reg({{phase}} - 1);
                s_output_regs(3)  <= s_buf_regs(0) AND NOT(s_derived_clock_reg({{phase}} - 1));
             END IF;
          END PROCESS makeOutputs;
          """);
    } else {
      Contents.add("""
          assign ClockBus = {GlobalClock,s_output_regs};
          always @(posedge GlobalClock)
          begin
             s_buf_regs[0]    <= s_derived_clock_reg[{{phase}} - 1];
             s_buf_regs[1]    <= ~s_derived_clock_reg[{{phase}} - 1];
             s_output_regs[0] <= s_buf_regs[0];
             s_output_regs[1] <= s_output_regs[1];
             s_output_regs[2] <= ~s_buf_regs[0] & s_derived_clock_reg[{{phase}} - 1];
             s_output_regs[3] <= ~s_derived_clock_reg[{{phase}} - 1] & s_buf_regs[0];
          end
          """);
    }
    Contents.add("").addRemarkBlock("Here the control signals are defined");
    if (HDL.isVHDL()) {
      Contents.add("""
          s_counter_is_zero <= '1' WHEN s_counter_reg = std_logic_vector(to_unsigned(0,{{nrOfBits}})) ELSE '0';
          s_counter_next    <= std_logic_vector(unsigned(s_counter_reg) - 1)
                                 WHEN s_counter_is_zero = '0' ELSE
                              std_logic_vector(to_unsigned(({{lowTick}}-1), {{nrOfBits}}))
                                 WHEN s_derived_clock_reg(0) = '1' ELSE
                              std_logic_vector(to_unsigned(({{highTick}}-1), {{nrOfBits}}));
          """);
    } else {
      Contents.add("""
              assign s_counter_is_zero = (s_counter_reg == 0) ? 1'b1 : 1'b0;
              assign s_counter_next = (s_counter_is_zero == 1'b0)
                                         ? s_counter_reg - 1
                                         : (s_derived_clock_reg[0] == 1'b1)
                                            ? {{lowTick}} - 1
                                            : {{highTick}} - 1;
              
              """)
          .addRemarkBlock("Here the initial values are defined (for simulation only)")
          .add("""
              initial
              begin
                 s_output_regs = 0;
                 s_derived_clock_reg = 0;
                 s_counter_reg = 0;
              end
              """);
    }
    Contents.add("").addRemarkBlock("Here the state registers are defined");
    if (HDL.isVHDL()) {
      Contents.add("""
          makeDerivedClock : PROCESS( GlobalClock , ClockTick , s_counter_is_zero ,
                                      s_derived_clock_reg)
          BEGIN
             IF (GlobalClock'event AND (GlobalClock = '1')) THEN
                IF (s_derived_clock_reg(0) /= '0' AND s_derived_clock_reg(0) /= '1') THEN --For simulation only
                   s_derived_clock_reg <= (OTHERS => '1');
                ELSIF (ClockTick = '1') THEN
                   FOR n IN {{phase}}-1 DOWNTO 1 LOOP
                     s_derived_clock_reg(n) <= s_derived_clock_reg(n-1);
                   END LOOP;
                   s_derived_clock_reg(0) <= s_derived_clock_reg(0) XOR s_counter_is_zero;
                END IF;
             END IF;
          END PROCESS makeDerivedClock;
          
          makeCounter : PROCESS( GlobalClock , ClockTick , s_counter_next ,
                                 s_derived_clock_reg )
          BEGIN
             IF (GlobalClock'event AND (GlobalClock = '1')) THEN
                IF (s_derived_clock_reg(0) /= '0' AND s_derived_clock_reg(0) /= '1') THEN --For simulation only
                   s_counter_reg <= (OTHERS => '0');
                ELSIF (ClockTick = '1') THEN
                   s_counter_reg <= s_counter_next;
                END IF;
             END IF;
          END PROCESS makeCounter;
          """);
    } else {
      Contents.add("""
          integer n;
          always @(posedge GlobalClock)
          begin
             if (ClockTick)
             begin
                s_derived_clock_reg[0] <= s_derived_clock_reg[0] ^ s_counter_is_zero;
                for (n = 1; n < {{phase}}; n = n+1) begin
                   s_derived_clock_reg[n] <= s_derived_clock_reg[n-1];
                end
             end
          end
          
          always @(posedge GlobalClock)
          begin
             if (ClockTick)
             begin
                s_counter_reg <= s_counter_next;
             end
          end
          """);
    }
    Contents.add("");
    return Contents.getWithIndent();
  }
}
