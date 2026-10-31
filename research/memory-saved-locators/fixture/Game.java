package rndguest;

public final class Game {
    public Player player = new Player(50);
    public Player[] party = {new Player(50), new Player(50)};
    public static int globalMoney = 15;
    public int unrelated = 50;

    public static class Player {
        private int money;
        public Player(int money) { this.money = money; }
        public int money() { return money; }
    }
}
