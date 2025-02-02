package com.luna.synthesis.features.cleanup;

import com.luna.synthesis.Synthesis;
import com.luna.synthesis.core.Config;
import com.luna.synthesis.events.packet.PacketReceivedEvent;
import com.luna.synthesis.utils.ChatLib;
import com.luna.synthesis.utils.Utils;
import net.minecraft.client.Minecraft;
import net.minecraft.event.HoverEvent;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class CoopCleanup {

    private final Config config = Synthesis.getInstance().getConfig();
    private boolean onContributions = false;
    private boolean shouldRemove = false;

    private List<String> messageQueue = new ArrayList<>();
    private boolean isDividerBlock = false;
    private int dividerBlockCap = 3;
    private IChatComponent theMessage = null;
    private final AtomicReference<String> price = new AtomicReference<>("");

    // I'm deeply terrified of regexes being called way too many times, as long as the message cannot be faked or give false positives, this will have to work.
    @SubscribeEvent
    public void onTooltip(ItemTooltipEvent event) {
        if (config.cleanupCoopCollections == 0) return;
        if (config.cleanupCoopCollections == 1) {
            event.toolTip.removeIf(s -> EnumChatFormatting.getTextWithoutFormattingCodes(s).endsWith(": 0"));
        } else if (config.cleanupCoopCollections == 2) {
            Iterator<String> iterator = event.toolTip.iterator();
            while (iterator.hasNext()) {
                String actualLine = EnumChatFormatting.getTextWithoutFormattingCodes(iterator.next());
                if (actualLine.equals("")) {
                    onContributions = false;
                }
                if (onContributions) {
                    String player = Minecraft.getMinecraft().getSession().getUsername();
                    if (!actualLine.contains(player)) {
                        iterator.remove();
                    }
                }
                if (actualLine.startsWith("Co-op Contributions:")) {
                    onContributions = true;
                }
            }
            onContributions = false;
        } else if (config.cleanupCoopCollections == 3) {
            Iterator<String> iterator = event.toolTip.iterator();
            while (iterator.hasNext()) {
                String actualLine = EnumChatFormatting.getTextWithoutFormattingCodes(iterator.next());
                if (actualLine.startsWith("Co-op Contributions:")) {
                    shouldRemove = true;
                }
                if (shouldRemove) {
                    iterator.remove();
                }
                if (actualLine.startsWith("Total Collected: ")) {
                    shouldRemove = true;
                }
                if (actualLine.equals("")) {
                    shouldRemove = false;
                }
            }
            shouldRemove = false;
        }
    }

    // It is not necessary to check hover action because the " » " changes color depending on party/coop member, but I realised that when this was already done so whatever
    @SubscribeEvent
    public void onChatMessage(ClientChatReceivedEvent event) {
        if (!config.cleanupCoopTravel) return;
        if (event.type != 0) return;
        String message = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (message.startsWith(" » ") && message.contains(" is traveling to ") && message.endsWith(" FOLLOW")) {
            if (event.message.getChatStyle() != null) {
                HoverEvent hover = event.message.getChatStyle().getChatHoverEvent();
                if (hover == null) return;
                if (hover.getAction() == HoverEvent.Action.SHOW_TEXT) {
                    String text = EnumChatFormatting.getTextWithoutFormattingCodes(hover.getValue().getUnformattedText());
                    if (!text.startsWith("SkyBlock Travel")) return;
                    if (text.split("\n").length >= 2) {
                        text = text.split("\n")[1];
                    }
                    if (!text.equals("Party member")) {
                        event.setCanceled(true);
                    }
                }
            }
        }
        if (!messageQueue.isEmpty() && !isDividerBlock) {
            if (event.message.getUnformattedText().equals(messageQueue.get(0))) {
                messageQueue.remove(0);
                event.setCanceled(true);
            }
        }
        if (event.message.getUnformattedText().startsWith("BIN Auction started for ")) {
            if (!price.get().equals("")) {
                event.message = new ChatComponentText(event.message.getFormattedText().replace("!", "") + EnumChatFormatting.YELLOW + " at " + EnumChatFormatting.GOLD + price.get() + " coins" + EnumChatFormatting.YELLOW + "!");
                price.set("");
            } else {
                theMessage = event.message;
                event.setCanceled(true);
            }
        }
    }

    // Used as a way to detect bulk messages (the ones between dividers) and filter out potential messages that got caught between them.
    @SubscribeEvent
    public void onPacketReceived(PacketReceivedEvent event) {
        if (event.getPacket() instanceof S02PacketChat) {
            S02PacketChat packet = (S02PacketChat) event.getPacket();
            if (packet.getType() != 2) {
                if (isDividerBlock) {
                    messageQueue.add(packet.getChatComponent().getUnformattedText());
                    if (dividerBlockCap > 0) {
                        dividerBlockCap--;
                    } else {
                        isDividerBlock = false;
                        messageQueue.clear();
                        dividerBlockCap = 5;
                    }
                }
                if (Utils.isDivider(packet.getChatComponent().getUnformattedText())) {
                    if (isDividerBlock) {
                        isDividerBlock = false;
                        String player = Minecraft.getMinecraft().getSession().getUsername();
                        messageQueue = messageQueue.stream().filter(s -> {
                            if (Utils.isDivider(s)) return true;
                            if (s.contains(" created a BIN auction for ") || s.contains(" created an auction for ")) {
                                switch (config.cleanupCoopAuctionCreation) {
                                    case 0:
                                        return false;
                                    case 1:
                                        return s.contains(player);
                                    case 2:
                                        if (s.contains(player) && s.endsWith(" coins!")) {
                                            price.set(s.split(" at ")[1].replace(" coins!", ""));
                                            return true;
                                        }
                                        return false;
                                    case 3:
                                        return !s.contains(player);
                                    case 4:
                                        return true;
                                    case 5:
                                        if (s.contains(player) && s.endsWith(" coins!")) {
                                            price.set(s.split(" at ")[1].replace(" coins!", ""));
                                        }
                                        return true;
                                }
                            } else if (s.contains(" cancelled an auction ")) {
                                switch (config.cleanupCoopAuctionCancellation) {
                                    case 0:
                                        return false;
                                    case 1:
                                        return s.contains(player);
                                    case 2:
                                        return !s.contains(player);
                                    case 3:
                                        return true;
                                }
                            } else if (s.contains(" collected an auction for ")) {
                                switch (config.cleanupCoopAuctionCollection) {
                                    case 0:
                                        return false;
                                    case 1:
                                        return s.contains(player);
                                    case 2:
                                        return !s.contains(player);
                                    case 3:
                                        return true;
                                }
                            } else if (s.contains(" has set the beacon profile stat to ")) {
                                switch (config.cleanupCoopBeaconStatChanges) {
                                    case 0:
                                        return false;
                                    case 1:
                                        return s.contains(player);
                                    case 2:
                                        return !s.contains(player);
                                    case 3:
                                        return true;
                                }
                            }
                            return false;
                        }).collect(Collectors.toList());
                        if (messageQueue.size() <= 2) messageQueue.clear();
                        if (theMessage != null && !price.get().equals("")) {
                            Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(theMessage.getFormattedText().replace("!", "") + EnumChatFormatting.YELLOW + " at " + EnumChatFormatting.GOLD + price.get() + " coins" + EnumChatFormatting.YELLOW + "!"));
                            theMessage = null;
                            price.set("");
                        }
                    } else {
                        dividerBlockCap = 5;
                        isDividerBlock = true;
                        messageQueue.add(packet.getChatComponent().getUnformattedText());
                    }
                }
            }
        }
    }
}
