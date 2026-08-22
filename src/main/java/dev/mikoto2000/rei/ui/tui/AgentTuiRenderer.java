package dev.mikoto2000.rei.ui.tui;

import java.util.List;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.paragraph.Paragraph;

final class AgentTuiRenderer {

  void render(Frame frame, AgentTuiRenderModel model) {
    if (frame.width() < 30 || frame.height() < 10) {
      frame.renderWidget(Paragraph.from("Terminal too small"), frame.area());
      return;
    }

    List<dev.tamboui.layout.Rect> areas = Layout.vertical()
        .constraints(Constraint.length(3), Constraint.fill(), Constraint.percentage(30), Constraint.length(3))
        .split(frame.area());

    Color statusColor = switch (model.status()) {
      case "RUNNING" -> Color.YELLOW;
      case "COMPLETED" -> Color.GREEN;
      case "FAILED" -> Color.RED;
      default -> Color.CYAN;
    };
    frame.renderWidget(Paragraph.builder()
        .text(Text.styled("rei                                      " + model.status(),
            Style.create().fg(statusColor).bold()))
        .block(Block.bordered())
        .build(), areas.get(0));

    int assistantHeight = Math.max(1, areas.get(1).height() - 2);
    int assistantLines = Math.max(1, model.assistantText().split("\\R", -1).length);
    frame.renderWidget(Paragraph.builder()
        .text(model.assistantText())
        .block(Block.builder().title("Assistant").build())
        .scroll(Math.max(0, assistantLines - assistantHeight))
        .build(), areas.get(1));

    frame.renderWidget(Paragraph.builder()
        .text(String.join(System.lineSeparator(), model.toolLines()))
        .block(Block.builder().title("Tools").build())
        .build(), areas.get(2));

    String prompt = model.agentRunning() ? "[RUNNING] " : "> ";
    frame.renderWidget(Paragraph.builder()
        .text(prompt + model.input())
        .block(Block.bordered())
        .build(), areas.get(3));
    int cursorWidth = Text.from(prompt + model.inputBeforeCursor()).width();
    frame.setCursorPosition(areas.get(3).x() + 1 + cursorWidth, areas.get(3).y() + 1);
  }
}
