/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package server.expeditions;

import client.Character;
import net.packet.Packet;
import net.server.PlayerStorage;
import net.server.Server;
import net.server.audit.locks.MonitoredLockType;
import net.server.audit.locks.MonitoredReentrantLock;
import net.server.audit.locks.factory.MonitoredReentrantLockFactory;
import net.server.channel.Channel;
import server.TimerManager;
import server.life.Monster;
import server.maps.MapleMap;
import tools.LogHelper;
import tools.PacketCreator;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * @author Alan (SharpAceX)
 */
public class Expedition {

    private static final int[] EXPEDITION_BOSSES = {
            8800000,// - Zakum's first body
            8800001,// - Zakum's second body
            8800002,// - Zakum's third body
            8800003,// - Zakum's Arm 1
            8800004,// - Zakum's Arm 2
            8800005,// - Zakum's Arm 3
            8800006,// - Zakum's Arm 4
            8800007,// - Zakum's Arm 5
            8800008,// - Zakum's Arm 6
            8800009,// - Zakum's Arm 7
            8800010,// - Zakum's Arm 8
            8810000,// - Horntail's Left Head
            8810001,// - Horntail's Right Head
            8810002,// - Horntail's Head A
            8810003,// - Horntail's Head B
            8810004,// - Horntail's Head C
            8810005,// - Horntail's Left Hand
            8810006,// - Horntail's Right Hand
            8810007,// - Horntail's Wings
            8810008,// - Horntail's Legs
            8810009,// - Horntail's Tails
            9420546,// - Scarlion Boss
            9420547,// - Scarlion Boss
            9420548,// - Angry Scarlion Boss
            9420549,// - Furious Scarlion Boss
            9420541,// - Targa
            9420542,// - Targa
            9420543,// - Angry Targa
            9420544,// - Furious Targa
    };

    private final Character leader;
    private final ExpeditionType type;
    private boolean registering;
    private final MapleMap startMap;
    private final List<String> bossLogs;
    private ScheduledFuture<?> schedule;
    private final Map<Integer, String> members = new ConcurrentHashMap<>();
    private final List<Integer> banned = new CopyOnWriteArrayList<>();
    private long startTime;
    private final Properties props = new Properties();
    private final boolean silent;
    private final int minSize;
    private final int maxSize;
    private final MonitoredReentrantLock pL = MonitoredReentrantLockFactory.createLock(MonitoredLockType.EIM_PARTY, true);

    public Expedition(Character player, ExpeditionType met, boolean sil, int minPlayers, int maxPlayers) {
        leader = player;
        members.put(player.getId(), player.getName());
        startMap = player.getMap();
        type = met;
        silent = sil;
        minSize = (minPlayers != 0) ? minPlayers : type.getMinSize();
        maxSize = (maxPlayers != 0) ? maxPlayers : type.getMaxSize();
        bossLogs = new CopyOnWriteArrayList<>();
    }

    public int getMinSize() {
        return minSize;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public void beginRegistration() {
        registering = true;
        leader.sendPacket(PacketCreator.getClock((int) MINUTES.toSeconds(type.getRegistrationMinutes())));
        if (!silent) {
            startMap.broadcastMessage(leader, PacketCreator.serverNotice(6, "[Expedition] " + leader.getName() + " has been declared the expedition captain. Please register for the expedition."), false);
            leader.sendPacket(PacketCreator.serverNotice(6, "[Expedition] You have become the expedition captain. Gather enough people for your team then talk to the NPC to start."));
        }
        scheduleRegistrationEnd();
    }

    private void scheduleRegistrationEnd() {
        final Expedition exped = this;
        startTime = System.currentTimeMillis() + MINUTES.toMillis(type.getRegistrationMinutes());

        schedule = TimerManager.getInstance().schedule(() -> {
            if (registering) {
                exped.removeChannelExpedition(startMap.getChannelServer());
                if (!silent) {
                    startMap.broadcastMessage(PacketCreator.serverNotice(6, "[Expedition] The time limit has been reached. Expedition has been disbanded."));
                }

                dispose(false);
            }
        }, MINUTES.toMillis(type.getRegistrationMinutes()));
    }

    public void dispose(boolean log) {
        broadcastExped(PacketCreator.removeClock());

        if (schedule != null) {
            schedule.cancel(false);
        }
        if (log && !registering) {
            LogHelper.logExpedition(this);
        }
    }

    public void finishRegistration() {
        registering = false;
    }

    public void start() {
        finishRegistration();
        registerExpeditionAttempt();
        broadcastExped(PacketCreator.removeClock());
        if (!silent) {
            broadcastExped(PacketCreator.serverNotice(6, "[Expedition] The expedition has started! Good luck, brave heroes!"));
        }
        startTime = System.currentTimeMillis();
        Server.getInstance().broadcastGMMessage(startMap.getWorld(), PacketCreator.serverNotice(6, "[Expedition] " + type.toString() + " Expedition started with leader: " + leader.getName()));
    }

    public String addMember(Character player) {
        if (!registering) {
            return "Sorry, this expedition is already underway. Registration is closed!";
        }
        if (banned.contains(player.getId())) {
            return "Sorry, you've been banned from this expedition by #b" + leader.getName() + "#k.";
        }
        if (members.size() >= this.getMaxSize()) { //Would be a miracle if anybody ever saw this
            return "Sorry, this expedition is full!";
        }

        int channel = this.getRecruitingMap().getChannelServer().getId();
        if (!ExpeditionBossLog.attemptBoss(player.getId(), channel, this, false)) {    // thanks Conrad, Cato for noticing some expeditions have entry limit
            return "Sorry, you've already reached the quota of attempts for this expedition! Try again another day...";
        }

        members.put(player.getId(), player.getName());
        player.sendPacket(PacketCreator.getClock((int) (startTime - System.currentTimeMillis()) / 1000));
        if (!silent) {
            broadcastExped(PacketCreator.serverNotice(6, "[Expedition] " + player.getName() + " has joined the expedition!"));
        }
        return "You have registered for the expedition successfully!";
    }

    public int addMemberInt(Character player) {
        if (!registering) {
            return 1; //"Sorry, this expedition is already underway. Registration is closed!";
        }
        if (banned.contains(player.getId())) {
            return 2; //"Sorry, you've been banned from this expedition by #b" + leader.getName() + "#k.";
        }
        if (members.size() >= this.getMaxSize()) { //Would be a miracle if anybody ever saw this
            return 3; //"Sorry, this expedition is full!";
        }

        members.put(player.getId(), player.getName());
        player.sendPacket(PacketCreator.getClock((int) (startTime - System.currentTimeMillis()) / 1000));
        if (!silent) {
            broadcastExped(PacketCreator.serverNotice(6, "[Expedition] " + player.getName() + " has joined the expedition!"));
        }
        return 0; //"You have registered for the expedition successfully!";
    }

    private void registerExpeditionAttempt() {
        int channel = this.getRecruitingMap().getChannelServer().getId();

        for (Character chr : getActiveMembers()) {
            ExpeditionBossLog.attemptBoss(chr.getId(), channel, this, true);
        }
    }

    private void broadcastExped(Packet packet) {
        for (Character chr : getActiveMembers()) {
            chr.sendPacket(packet);
        }
    }

    public boolean removeMember(Character chr) {
        if (members.remove(chr.getId()) != null) {
            chr.sendPacket(PacketCreator.removeClock());
            if (!silent) {
                broadcastExped(PacketCreator.serverNotice(6, "[Expedition] " + chr.getName() + " has left the expedition."));
                chr.dropMessage(6, "[Expedition] You have left this expedition.");
            }
            return true;
        }

        return false;
    }

    public void ban(Entry<Integer, String> chr) {
        int cid = chr.getKey();
        if (!banned.contains(cid)) {
            banned.add(cid);
            members.remove(cid);

            if (!silent) {
                broadcastExped(PacketCreator.serverNotice(6, "[Expedition] " + chr.getValue() + " has been banned from the expedition."));
            }

            Character player = startMap.getWorldServer().getPlayerStorage().getCharacterById(cid);
            if (player != null && player.isLoggedinWorld()) {
                player.sendPacket(PacketCreator.removeClock());
                if (!silent) {
                    player.dropMessage(6, "[Expedition] You have been banned from this expedition.");
                }
                if (ExpeditionType.ARIANT.equals(type) || ExpeditionType.ARIANT1.equals(type) || ExpeditionType.ARIANT2.equals(type)) {
                    player.changeMap(980010000);
                }
            }
        }
    }

    public void monsterKilled(Character chr, Monster mob) {
        for (int expeditionBoss : EXPEDITION_BOSSES) {
            if (mob.getId() == expeditionBoss) { //If the monster killed was a boss
                String timeStamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
                bossLogs.add(">" + mob.getName() + " was killed after " + LogHelper.getTimeString(startTime) + " - " + timeStamp + "\r\n");
                return;
            }
        }
    }

    public void setProperty(String key, String value) {
        pL.lock();
        try {
            props.setProperty(key, value);
        } finally {
            pL.unlock();
        }
    }

    public String getProperty(String key) {
        pL.lock();
        try {
            return props.getProperty(key);
        } finally {
            pL.unlock();
        }
    }

    public ExpeditionType getType() {
        return type;
    }

    public List<Character> getActiveMembers() {    // thanks MedicOP for figuring out an issue with broadcasting packets to offline members
        PlayerStorage ps = startMap.getWorldServer().getPlayerStorage();

        List<Character> activeMembers = new LinkedList<>();
        for (Integer chrid : getMembers().keySet()) {
            Character chr = ps.getCharacterById(chrid);
            if (chr != null && chr.isLoggedinWorld()) {
                activeMembers.add(chr);
            }
        }

        return activeMembers;
    }

    public Map<Integer, String> getMembers() {
        return new HashMap<>(members);
    }

    public List<Entry<Integer, String>> getMemberList() {
        List<Entry<Integer, String>> memberList = new LinkedList<>();
        Entry<Integer, String> leaderEntry = null;

        for (Entry<Integer, String> e : getMembers().entrySet()) {
            if (!isLeader(e.getKey())) {
                memberList.add(e);
            } else {
                leaderEntry = e;
            }
        }

        if (leaderEntry != null) {
            memberList.add(0, leaderEntry);
        }

        return memberList;
    }

    public final boolean isExpeditionTeamTogether() {
        List<Character> chars = getActiveMembers();
        if (chars.size() <= 1) {
            return true;
        }

        Iterator<Character> iterator = chars.iterator();
        Character mc = iterator.next();
        int mapId = mc.getMapId();

        for (; iterator.hasNext(); ) {
            mc = iterator.next();
            if (mc.getMapId() != mapId) {
                return false;
            }
        }

        return true;
    }

    public final void warpExpeditionTeam(int warpFrom, int warpTo) {
        List<Character> players = getActiveMembers();

        for (Character chr : players) {
            if (chr.getMapId() == warpFrom) {
                chr.changeMap(warpTo);
            }
        }
    }

    public final void warpExpeditionTeam(int warpTo) {
        List<Character> players = getActiveMembers();

        for (Character chr : players) {
            chr.changeMap(warpTo);
        }
    }

    public final void warpExpeditionTeamToMapSpawnPoint(int warpFrom, int warpTo, int toSp) {
        List<Character> players = getActiveMembers();

        for (Character chr : players) {
            if (chr.getMapId() == warpFrom) {
                chr.changeMap(warpTo, toSp);
            }
        }
    }

    public final void warpExpeditionTeamToMapSpawnPoint(int warpTo, int toSp) {
        List<Character> players = getActiveMembers();

        for (Character chr : players) {
            chr.changeMap(warpTo, toSp);
        }
    }

    public final boolean addChannelExpedition(Channel ch) {
        return ch.addExpedition(this);
    }

    public final void removeChannelExpedition(Channel ch) {
        ch.removeExpedition(this);
    }

    public Character getLeader() {
        return leader;
    }

    public MapleMap getRecruitingMap() {
        return startMap;
    }

    public boolean contains(Character player) {
        return members.containsKey(player.getId()) || isLeader(player);
    }

    public boolean isLeader(Character player) {
        return isLeader(player.getId());
    }

    public boolean isLeader(int playerid) {
        return leader.getId() == playerid;
    }

    public boolean isRegistering() {
        return registering;
    }

    public boolean isInProgress() {
        return !registering;
    }

    public long getStartTime() {
        return startTime;
    }

    public List<String> getBossLogs() {
        return bossLogs;
    }
}
